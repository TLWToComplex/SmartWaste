"""在已配置环境变量的终端中执行 python run.py。"""
import os

import uvicorn


if __name__ == "__main__":
    uvicorn.run("app:app", host=os.getenv("SMARTWASTE_VISION_HOST", "127.0.0.1"),
                port=int(os.getenv("SMARTWASTE_VISION_PORT", "8082")), workers=1, limit_concurrency=16)
