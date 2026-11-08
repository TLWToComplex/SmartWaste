from __future__ import annotations

import math
from pathlib import Path
from typing import Any

import torch
from PIL import Image, ImageOps
from torch import nn
from torchvision import models, transforms


CATEGORIES = {
    "recyclable": {"sortId": 1, "sort": "可回收垃圾"},
    "hazardous": {"sortId": 2, "sort": "有害垃圾"},
    "kitchen": {"sortId": 3, "sort": "湿垃圾"},
    "other": {"sortId": 4, "sort": "干垃圾"},
}
PREPROCESS = {
    "image_size": 224,
    "resize_size": 256,
    "mean": [0.485, 0.456, 0.406],
    "std": [0.229, 0.224, 0.225],
}


def create_model(num_classes: int, *, pretrained: bool = False) -> nn.Module:
    if not 2 <= num_classes <= 1000:
        raise ValueError("分类数须在 2 至 1000 之间")
    weights = models.ResNet18_Weights.IMAGENET1K_V1 if pretrained else None
    model = models.resnet18(weights=weights)
    model.fc = nn.Linear(model.fc.in_features, num_classes)
    return model


def image_transform(*, training: bool = False) -> transforms.Compose:
    spatial = [transforms.RandomResizedCrop(PREPROCESS["image_size"]), transforms.RandomHorizontalFlip()] if training else [
        transforms.Resize(PREPROCESS["resize_size"]), transforms.CenterCrop(PREPROCESS["image_size"])
    ]
    return transforms.Compose(spatial + [transforms.ToTensor(), transforms.Normalize(PREPROCESS["mean"], PREPROCESS["std"])])


def load_rgb(path: str | Path) -> Image.Image:
    with Image.open(path) as image:
        return ImageOps.exif_transpose(image).convert("RGB")


def validate_labels(labels: Any, classes: list[str]) -> dict[str, dict[str, str]]:
    if not isinstance(labels, dict) or set(labels) != set(classes):
        raise ValueError("标签映射的键必须与图片类别文件夹完全一致")
    result = {}
    for key in classes:
        item = labels[key]
        if not isinstance(item, dict) or item.get("category") not in CATEGORIES:
            raise ValueError(f"类别 {key} 缺少合法的垃圾类别映射")
        name = item.get("name")
        if not isinstance(name, str) or not name.strip() or len(name) > 120:
            raise ValueError(f"类别 {key} 的显示名称无效")
        result[key] = {"name": name.strip(), "category": item["category"]}
    return result


def load_checkpoint(path: Path) -> tuple[nn.Module, dict]:
    # 只接受本地训练产出的 state_dict 和基础类型元数据，不反序列化任意 Python 对象。
    checkpoint = torch.load(path, map_location="cpu", weights_only=True)
    if not isinstance(checkpoint, dict) or checkpoint.get("format_version") != 1 or checkpoint.get("architecture") != "resnet18":
        raise ValueError("模型文件格式不受支持，请使用本项目训练脚本生成权重")
    classes = checkpoint.get("classes")
    if not isinstance(classes, list) or not all(isinstance(c, str) and c for c in classes) or len(set(classes)) != len(classes):
        raise ValueError("模型类别顺序无效")
    if checkpoint.get("class_to_idx") != {name: i for i, name in enumerate(classes)}:
        raise ValueError("模型类别编号不匹配")
    checkpoint["labels"] = validate_labels(checkpoint.get("labels"), classes)
    if checkpoint.get("preprocess") != PREPROCESS:
        raise ValueError("训练与推理的预处理参数不一致")
    if not isinstance(checkpoint.get("model_version"), str) or not checkpoint["model_version"].strip():
        raise ValueError("模型版本缺失")
    # 必须包含实际完成的预训练骨干微调，不能把随机初始化权重当成垃圾识别模型。
    if checkpoint.get("pretrained_weights") != "IMAGENET1K_V1" or checkpoint.get("fine_tuned") is not True:
        raise ValueError("权重未完成预训练骨干微调")
    accuracy = checkpoint.get("val_accuracy")
    if not isinstance(accuracy, (float, int)) or not math.isfinite(accuracy) or not 0 <= accuracy <= 1:
        raise ValueError("模型缺少有效的验证集指标")
    model = create_model(len(classes))
    model.load_state_dict(checkpoint["state_dict"], strict=True)
    model.eval()
    return model, checkpoint
