from __future__ import annotations

import io
import warnings
from threading import Lock

import torch
from PIL import Image, ImageOps, UnidentifiedImageError

from .model import CATEGORIES, image_transform, load_checkpoint
from .settings import Settings


class InvalidImage(ValueError):
    pass


class Predictor:
    def __init__(self, settings: Settings):
        self.settings = settings
        torch.set_num_threads(settings.torch_threads)
        if settings.device == "cuda" and not torch.cuda.is_available():
            raise ValueError("配置为 CUDA 推理，但当前环境没有可用 CUDA 设备")
        self.device = torch.device("cuda" if settings.device == "auto" and torch.cuda.is_available() else
                                   "cpu" if settings.device == "auto" else settings.device)
        self.model, self.metadata = load_checkpoint(settings.checkpoint)
        self.model.to(self.device)
        self.transform = image_transform()
        self.lock = Lock()
        # 单次预热；缺失权重或损坏参数不会以随机预测结果冒充服务就绪。
        with torch.inference_mode():
            result = self.model(torch.zeros(1, 3, 224, 224, device=self.device))
            if not torch.isfinite(result).all().item():
                raise ValueError("模型权重输出非有限数值")

    def decode(self, content: bytes) -> Image.Image:
        if not content or len(content) > self.settings.max_bytes:
            raise InvalidImage("图片为空或超过大小限制")
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("error", Image.DecompressionBombWarning)
                with Image.open(io.BytesIO(content)) as image:
                    if image.format not in {"JPEG", "PNG", "WEBP"}:
                        raise InvalidImage("仅支持 JPEG、PNG、WebP 图片")
                    if image.width * image.height > self.settings.max_pixels:
                        raise InvalidImage("图片分辨率过高，请缩小后上传")
                    if getattr(image, "n_frames", 1) != 1:
                        raise InvalidImage("请上传静态图片，不支持动画或多帧图片")
                    image.load()
                    return ImageOps.exif_transpose(image).convert("RGB")
        except InvalidImage:
            raise
        except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning) as exc:
            raise InvalidImage("图片无法解码，请重新上传有效的 JPEG、PNG 或 WebP 图片") from exc

    def predict(self, image: Image.Image) -> dict:
        # 单 worker 中串行运行模型，避免多个 CPU/GPU 前向计算争用设备内存。
        with self.lock, torch.inference_mode():
            batch = self.transform(image).unsqueeze(0).to(self.device)
            probabilities = self.model(batch).softmax(dim=1)[0]
            if not torch.isfinite(probabilities).all().item():
                raise RuntimeError("模型预测结果无效")
            scores, positions = probabilities.topk(min(3, len(self.metadata["classes"])))
            candidates = []
            for score, position in zip(scores.cpu().tolist(), positions.cpu().tolist()):
                key = self.metadata["classes"][position]
                label = self.metadata["labels"][key]
                candidates.append({"label": key, "name": label["name"], "category": label["category"],
                                   **CATEGORIES[label["category"]], "confidence": float(score)})
        best = candidates[0]
        uncertain = best["confidence"] < self.settings.confidence_threshold
        return {
            **best,
            "modelVersion": self.metadata["model_version"],
            "uncertain": uncertain,
            "message": "识别结果不确定，请重新拍摄单件垃圾或使用文字查询核对分类。" if uncertain else
                       "识别结果仅供辅助判断，投放请以当地分类规则为准。",
            "candidates": candidates,
        }
