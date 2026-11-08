"""将按类别存放的原始图片划分为 train/val，原始目录只读。"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import shutil
from pathlib import Path

from PIL import Image


def prepare(source: Path, output: Path, val_ratio: float, seed: int) -> None:
    source, output = source.resolve(), output.resolve()
    if not source.is_dir() or not 0 < val_ratio < 1:
        raise ValueError("请提供有效原始图片目录，验证集比例须在 0 至 1 之间")
    if output.is_relative_to(source) or source.is_relative_to(output):
        raise ValueError("输出目录与原始图片目录不能互相包含")
    if output.exists() and any(output.iterdir()):
        raise ValueError("输出目录必须为空，避免覆盖已有数据集")
    classes = sorted(p for p in source.iterdir() if p.is_dir() and not p.name.startswith("."))
    if not 2 <= len(classes) <= 1000:
        raise ValueError("原始目录下须有 2 至 1000 个类别文件夹")
    rng = random.Random(seed)
    seen: dict[str, str] = {}
    planned: list[tuple[Path, str, str, str]] = []
    counts = {}
    duplicates = 0
    for folder in classes:
        unique = []
        for image in sorted(folder.rglob("*")):
            if not image.is_file() or image.suffix.lower() not in {".jpg", ".jpeg", ".png", ".webp"}:
                continue
            with Image.open(image) as decoded:
                if decoded.format not in {"JPEG", "PNG", "WEBP"} or getattr(decoded, "n_frames", 1) != 1:
                    raise ValueError(f"图片格式不支持或包含多帧: {image}")
                decoded.verify()
            digest = hashlib.sha256(image.read_bytes()).hexdigest()
            if digest in seen:
                if seen[digest] != folder.name:
                    raise ValueError(f"相同图片出现在不同类别中，请核对标签: {image}")
                duplicates += 1
                continue
            seen[digest] = folder.name
            unique.append((image, digest))
        if len(unique) < 2:
            raise ValueError(f"类别 {folder.name} 去重后至少需要 2 张图片才能划分训练和验证集")
        rng.shuffle(unique)
        val_count = max(1, min(len(unique) - 1, round(len(unique) * val_ratio)))
        counts[folder.name] = {"train": len(unique) - val_count, "val": val_count}
        for index, (image, digest) in enumerate(unique):
            planned.append((image, "val" if index < val_count else "train", folder.name, digest))
    # 所有图片和标签检查完成后才开始复制；完全重复的图片不会跨 train/val 泄漏。
    manifest = []
    for image, split, label, digest in planned:
        target = output / split / label / (digest + image.suffix.lower())
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(image, target)
        manifest.append({"file": target.relative_to(output).as_posix(), "class": label, "sha256": digest})
    report = {"seed": seed, "val_ratio": val_ratio, "counts": counts, "duplicates_removed": duplicates, "files": manifest}
    (output / "split-manifest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"counts": counts, "duplicates_removed": duplicates}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--val-ratio", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()
    prepare(args.source, args.output, args.val_ratio, args.seed)
