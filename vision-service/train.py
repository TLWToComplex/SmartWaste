"""基于 ImageNet ResNet18 先训练分类头，再微调 layer4 或全部骨干。"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import random
import tempfile
from datetime import datetime, timezone
from pathlib import Path

import torch
from torch import nn
from torch.utils.data import DataLoader
from torchvision.datasets import ImageFolder

from vision.model import PREPROCESS, create_model, image_transform, load_rgb, validate_labels


def atomic_save(checkpoint: dict, destination: Path) -> None:
    descriptor, temporary = tempfile.mkstemp(dir=destination.parent, suffix=".tmp")
    os.close(descriptor)
    try:
        torch.save(checkpoint, temporary)
        os.replace(temporary, destination)
    finally:
        Path(temporary).unlink(missing_ok=True)


def check_split(train: ImageFolder, val: ImageFolder) -> None:
    if train.class_to_idx != val.class_to_idx:
        raise ValueError("训练集与验证集的类别文件夹不一致")
    # 即使用户绕过 prepare_dataset，也拒绝完全重复图片造成的验证集泄漏和标签冲突。
    hashes: dict[str, tuple[int, str]] = {}
    for split, data in (("train", train), ("val", val)):
        for filename, label in data.samples:
            digest = hashlib.sha256(Path(filename).read_bytes()).hexdigest()
            previous = hashes.get(digest)
            if previous is not None and (previous[0] != label or previous[1] != split):
                raise ValueError(f"图片标签冲突或 train/val 存在重复图片: {filename}")
            hashes[digest] = (label, split)


def configure_stage(model: nn.Module, stage: str, scope: str, head_lr: float, backbone_lr: float):
    for parameter in model.parameters():
        parameter.requires_grad_(False)
    for parameter in model.fc.parameters():
        parameter.requires_grad_(True)
    groups = [{"params": list(model.fc.parameters()), "lr": head_lr}]
    if stage == "finetune":
        backbone = []
        for name, parameter in model.named_parameters():
            if not name.startswith("fc.") and (scope == "all" or name.startswith("layer4.")):
                parameter.requires_grad_(True)
                backbone.append(parameter)
        groups.append({"params": backbone, "lr": backbone_lr})
    return torch.optim.AdamW(groups, weight_decay=1e-4)


def set_training_mode(model: nn.Module, stage: str, scope: str) -> None:
    # 冻结骨干时同时冻结 BatchNorm 的 running stats，不能只关闭梯度。
    model.eval()
    model.fc.train()
    if stage == "finetune":
        if scope == "all":
            model.train()
        else:
            model.layer4.train()


def epoch(model: nn.Module, loader: DataLoader, device: torch.device, criterion: nn.Module,
          optimizer=None, stage: str = "head", scope: str = "layer4") -> tuple[float, float]:
    training = optimizer is not None
    if training:
        set_training_mode(model, stage, scope)
    else:
        model.eval()
    total_loss, correct, total = 0.0, 0, 0
    with torch.set_grad_enabled(training):
        for images, labels in loader:
            images, labels = images.to(device), labels.to(device)
            if training:
                optimizer.zero_grad(set_to_none=True)
            logits = model(images)
            loss = criterion(logits, labels)
            if not torch.isfinite(loss).item():
                raise ValueError("训练或验证损失出现非有限数值，请检查数据和学习率")
            if training:
                loss.backward()
                optimizer.step()
            total_loss += loss.item() * labels.size(0)
            correct += (logits.argmax(1) == labels).sum().item()
            total += labels.size(0)
    return total_loss / total, correct / total


def train(args) -> None:
    if args.head_epochs < 1 or args.finetune_epochs < 1 or args.batch_size < 2 or args.workers < 0:
        raise ValueError("两个阶段都至少训练 1 轮，批大小至少为 2，加载进程数不能为负")
    if not all(math.isfinite(x) and x > 0 for x in (args.head_lr, args.backbone_lr)):
        raise ValueError("学习率必须是正的有限数值")
    random.seed(args.seed)
    torch.manual_seed(args.seed)
    if args.device == "cuda" and not torch.cuda.is_available():
        raise ValueError("指定 CUDA，但当前环境无可用 GPU")
    device = torch.device("cuda" if args.device == "auto" and torch.cuda.is_available() else
                          "cpu" if args.device == "auto" else args.device)
    train_set = ImageFolder(args.data / "train", transform=image_transform(training=True), loader=load_rgb)
    val_set = ImageFolder(args.data / "val", transform=image_transform(), loader=load_rgb)
    check_split(train_set, val_set)
    labels = validate_labels(json.loads(args.labels.read_text(encoding="utf-8-sig")), train_set.classes)
    if len(train_set) < 2:
        raise ValueError("训练图片不足")
    # 把仅剩 1 张图片的小批次丢弃，避免微调 BatchNorm 时批大小为 1。
    batch_size = min(args.batch_size, len(train_set))
    generator = torch.Generator().manual_seed(args.seed)
    train_loader = DataLoader(train_set, batch_size=batch_size, shuffle=True, num_workers=args.workers,
                              drop_last=len(train_set) % batch_size == 1, generator=generator)
    val_loader = DataLoader(val_set, batch_size=args.batch_size, shuffle=False, num_workers=args.workers)
    # 只有训练阶段允许下载预训练权重；推理启动不会联网下载模型。
    model = create_model(len(train_set.classes), pretrained=True).to(device)
    criterion = nn.CrossEntropyLoss()
    version = datetime.now(timezone.utc).strftime("resnet18-%Y%m%dT%H%M%S%fZ")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    run_directory = output / version
    run_directory.mkdir()  # 每次训练保留独立记录，避免覆盖历史指标。
    history = []
    best_finetune_accuracy = -1.0
    global_epoch = 0
    for stage, rounds in (("head", args.head_epochs), ("finetune", args.finetune_epochs)):
        optimizer = configure_stage(model, stage, args.finetune_scope, args.head_lr, args.backbone_lr)
        scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=rounds)
        for _ in range(rounds):
            global_epoch += 1
            train_loss, train_accuracy = epoch(model, train_loader, device, criterion, optimizer, stage, args.finetune_scope)
            val_loss, val_accuracy = epoch(model, val_loader, device, criterion)
            scheduler.step()
            metrics = {"epoch": global_epoch, "stage": stage, "train_loss": train_loss,
                       "train_accuracy": train_accuracy, "val_loss": val_loss, "val_accuracy": val_accuracy}
            history.append(metrics)
            print(json.dumps(metrics, ensure_ascii=False), flush=True)
            (run_directory / "metrics.json").write_text(json.dumps(history, indent=2), encoding="utf-8")
            # best.pt 仅来自真正更新过预训练骨干的阶段，而非仅训练分类头的阶段。
            if stage == "finetune" and val_accuracy > best_finetune_accuracy:
                best_finetune_accuracy = val_accuracy
                checkpoint = {
                    "format_version": 1, "architecture": "resnet18", "model_version": version,
                    "pretrained_weights": "IMAGENET1K_V1", "fine_tuned": True,
                    "finetune_scope": args.finetune_scope, "epoch": global_epoch,
                    "val_accuracy": val_accuracy, "val_loss": val_loss,
                    "classes": train_set.classes, "class_to_idx": train_set.class_to_idx,
                    "labels": labels, "preprocess": PREPROCESS,
                    "train_samples": len(train_set), "val_samples": len(val_set), "seed": args.seed,
                    "training_config": {"head_epochs": args.head_epochs, "finetune_epochs": args.finetune_epochs,
                                        "head_lr": args.head_lr, "backbone_lr": args.backbone_lr,
                                        "batch_size": batch_size},
                    "state_dict": {name: tensor.detach().cpu().clone() for name, tensor in model.state_dict().items()},
                }
                atomic_save(checkpoint, run_directory / "best.pt")
    # 完整训练完成后才发布默认服务权重；中途失败不覆盖之前已发布的 best.pt。
    best = torch.load(run_directory / "best.pt", map_location="cpu", weights_only=True)
    atomic_save(best, output / "best.pt")
    print(f"训练完成，微调阶段最佳验证准确率: {best_finetune_accuracy:.4f}，权重: {output / 'best.pt'}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, required=True, help="包含 train/ 与 val/ 的数据集目录")
    parser.add_argument("--labels", type=Path, required=True, help="类别文件夹名到显示名称及垃圾类别的 JSON 映射")
    parser.add_argument("--output", type=Path, default=Path("checkpoints"))
    parser.add_argument("--head-epochs", type=int, default=3)
    parser.add_argument("--finetune-epochs", type=int, default=7)
    parser.add_argument("--finetune-scope", choices=["layer4", "all"], default="layer4")
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--head-lr", type=float, default=1e-3)
    parser.add_argument("--backbone-lr", type=float, default=1e-4)
    parser.add_argument("--device", choices=["cpu", "cuda", "auto"], default="auto")
    parser.add_argument("--workers", type=int, default=0, help="Windows 默认使用 0，避免多进程启动开销")
    parser.add_argument("--seed", type=int, default=42)
    train(parser.parse_args())
