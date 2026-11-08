from __future__ import annotations

import logging
import secrets
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from vision.predictor import InvalidImage, Predictor
from vision.settings import Settings


logger = logging.getLogger("smartwaste.vision")


class Candidate(BaseModel):
    label: str
    name: str
    category: str
    sortId: int
    sort: str
    confidence: float = Field(ge=0, le=1)


class Prediction(Candidate):
    modelVersion: str
    uncertain: bool
    message: str
    candidates: list[Candidate]


@asynccontextmanager
async def lifespan(application: FastAPI):
    application.state.settings = Settings.from_env()
    application.state.predictor = None
    application.state.model_error = "MODEL_NOT_READY"
    # 固定上限包含正在解码/等待推理的请求；忙时立即返回，不无限积压图片。
    application.state.inflight = 0
    try:
        application.state.predictor = await run_in_threadpool(Predictor, application.state.settings)
        application.state.model_error = None
        logger.info("垃圾识别模型已加载")
    except FileNotFoundError:
        application.state.model_error = "CHECKPOINT_MISSING"
        logger.warning("缺少训练权重，请先运行微调脚本或配置有效权重路径")
    except Exception as exc:
        application.state.model_error = "CHECKPOINT_LOAD_FAILED"
        logger.warning("模型加载失败: %s", type(exc).__name__)
    yield
    application.state.predictor = None


app = FastAPI(title="SmartWaste Vision", version="1.0.0", lifespan=lifespan,
              docs_url=None, redoc_url=None, openapi_url=None)


@app.middleware("http")
async def limit_upload(request: Request, call_next):
    # 在解析 multipart 之前鉴权和限制请求总大小，避免上传完才拒绝超大请求。
    settings = app.state.settings
    supplied = request.headers.get("x-vision-token", "").encode("utf-8")
    if not secrets.compare_digest(settings.token.encode("utf-8"), supplied):
        return JSONResponse(status_code=401, content={"detail": "内部服务认证失败"})
    if request.method == "POST":
        raw_length = request.headers.get("content-length")
        if raw_length is None or request.headers.get("transfer-encoding"):
            return JSONResponse(status_code=411, content={"detail": "请使用带 Content-Length 的上传请求"})
        try:
            length = int(raw_length)
        except ValueError:
            return JSONResponse(status_code=400, content={"detail": "请求长度无效"})
        if length < 0 or length > settings.max_bytes + 65536:
            return JSONResponse(status_code=413, content={"detail": "图片超过上传大小限制"})
    return await call_next(request)


async def authorize(x_vision_token: Annotated[str | None, Header()] = None):
    configured = app.state.settings.token.encode("utf-8")
    if x_vision_token is None or not secrets.compare_digest(configured, x_vision_token.encode("utf-8")):
        raise HTTPException(status_code=401, detail="内部服务认证失败")


@app.get("/internal/vision/health", dependencies=[Depends(authorize)])
async def health():
    predictor = app.state.predictor
    if predictor is None:
        return JSONResponse(status_code=503, content={"ready": False, "reason": app.state.model_error})
    return {"ready": True, "modelVersion": predictor.metadata["model_version"],
            "device": str(predictor.device), "classes": len(predictor.metadata["classes"])}


@app.post("/internal/vision/predict", response_model=Prediction, dependencies=[Depends(authorize)])
async def predict(file: UploadFile):
    image = None
    acquired = False
    try:
        predictor = app.state.predictor
        if predictor is None:
            raise HTTPException(status_code=503, detail="模型尚未就绪，请先训练并加载有效权重")
        if app.state.inflight >= 4:
            raise HTTPException(status_code=429, detail="识别服务繁忙，请稍后重试", headers={"Retry-After": "2"})
        app.state.inflight += 1
        acquired = True
        if file.content_type not in {"image/jpeg", "image/png", "image/webp"}:
            raise HTTPException(status_code=415, detail="仅支持 JPEG、PNG、WebP 图片")
        limit = app.state.settings.max_bytes
        content = await file.read(limit + 1)
        if len(content) > limit:
            raise HTTPException(status_code=413, detail="图片超过上传大小限制")
        image = await run_in_threadpool(predictor.decode, content)
        return await run_in_threadpool(predictor.predict, image)
    except InvalidImage as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except HTTPException:
        raise
    except Exception as exc:
        logger.warning("图片推理未完成: %s", type(exc).__name__)
        raise HTTPException(status_code=503, detail="图片识别暂不可用，请稍后重试") from exc
    finally:
        if image is not None:
            image.close()
        if acquired:
            app.state.inflight -= 1
        await file.close()
