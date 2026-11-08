from __future__ import annotations

import math
import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    token: str
    checkpoint: Path
    device: str
    max_bytes: int
    max_pixels: int
    confidence_threshold: float
    torch_threads: int

    @classmethod
    def from_env(cls) -> "Settings":
        root = Path(__file__).resolve().parents[1]
        token = os.getenv("SMARTWASTE_VISION_INTERNAL_TOKEN", "")
        if len(token) < 32:
            raise ValueError("SMARTWASTE_VISION_INTERNAL_TOKEN 至少需要 32 个字符")
        settings = cls(
            token=token,
            checkpoint=Path(os.getenv("SMARTWASTE_VISION_CHECKPOINT", str(root / "checkpoints" / "best.pt"))).resolve(),
            device=os.getenv("SMARTWASTE_VISION_DEVICE", "cpu"),
            max_bytes=int(os.getenv("SMARTWASTE_VISION_MAX_BYTES", str(5 * 1024 * 1024))),
            max_pixels=int(os.getenv("SMARTWASTE_VISION_MAX_PIXELS", "24000000")),
            confidence_threshold=float(os.getenv("SMARTWASTE_VISION_CONFIDENCE_THRESHOLD", "0.65")),
            torch_threads=int(os.getenv("SMARTWASTE_VISION_TORCH_THREADS", "2")),
        )
        if settings.device not in {"cpu", "cuda", "auto"}:
            raise ValueError("推理设备只支持 cpu、cuda、auto")
        if not 1024 <= settings.max_bytes <= 6 * 1024 * 1024 or not 224 * 224 <= settings.max_pixels <= 50000000:
            raise ValueError("图片大小或像素限制无效")
        if not math.isfinite(settings.confidence_threshold) or not 0 <= settings.confidence_threshold <= 1:
            raise ValueError("置信度阈值应在 0 至 1 之间")
        if not 1 <= settings.torch_threads <= 32:
            raise ValueError("推理线程数应在 1 至 32 之间")
        return settings
