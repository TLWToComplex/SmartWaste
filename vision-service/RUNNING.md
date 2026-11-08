# 拍照识别服务本地运行

`vision-service` 是独立的 Python 模块，与 `rag-service` 并列。默认调用链：用户上传图片 → Spring Boot `8080` 的 `POST /classification` → FastAPI `8082` 的 `POST /internal/vision/predict` → ResNet18 推理 → 返回类别和置信度。用户端只访问业务后端，内部密钥不会发送给浏览器。当前拍照识别已替换原来的百度 EasyDL 调用。

## 安装环境

在本目录使用 Python 3.10 或更新版本创建独立环境：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

训练和部署使用相容的 PyTorch、torchvision 版本。使用 CUDA 时，根据自己的显卡与驱动安装匹配版本；CPU 推理可以使用 CPU 版 PyTorch。安装依赖不会自动训练模型。

## 准备数据

原始图片按类别存放，例如：

```text
data/raw/
  recyclable/     可回收垃圾图片
  hazardous/      有害垃圾图片
  kitchen/        湿垃圾图片
  other/          干垃圾图片
```

`labels.example.json` 的键必须与文件夹名完全一致。可以直接识别四类，也可以使用 `plastic_bottle`、`used_tissue` 等细分类别：为每个文件夹提供对应的 `name` 和 `category`。`category` 只能是 `recyclable`、`hazardous`、`kitchen`、`other`，映射到现有系统的类别编号 1、2、3、4。实际标签应结合所用数据集和本地分类要求核对，代码不会自动推断类别。

使用自己有权使用的图片和正确标签；图片应以单件垃圾为主要主体。整理为训练集与验证集：

```powershell
.\.venv\Scripts\python.exe prepare_dataset.py --source data/raw --output data/prepared --val-ratio 0.2
```

脚本验证图片格式、按类别划分 train/val 并生成拆分清单；同类别完全重复图片会去重，不同类别出现相同图片会报错。输出目录必须为空，不覆盖原始数据。至少每类两张图片只是脚本能划分的下限，不能作为模型已具备识别能力的依据。近似重复图片、同一物品的连续照片等应人工按来源分组，避免跨训练/验证集造成指标偏高。

## 迁移学习微调

```powershell
.\.venv\Scripts\python.exe train.py --data data/prepared --labels labels.example.json --output checkpoints --head-epochs 3 --finetune-epochs 7 --device auto
```

第一次训练会下载 ImageNet ResNet18 预训练权重。先冻结骨干、训练新分类头，再以较小学习率微调 `layer4`；`--finetune-scope all` 可调整全部骨干。冻结层的 BatchNorm 运行统计也会冻结。Windows 默认数据加载进程数为 0；支持通过 `--workers`、`--batch-size`、`--head-lr`、`--backbone-lr` 调整。

脚本每轮记录训练损失、训练准确率、验证损失和验证准确率。每次训练保存到独立版本目录，最佳微调权重包含 `state_dict`、类别顺序、类别编号、垃圾类别映射、预处理参数及验证指标。只有完整训练成功后才原子更新 `checkpoints/best.pt`；中途失败保留之前发布的模型。原始图片、拆分数据、虚拟环境和模型权重均已加入 Git 忽略规则。

训练是离线操作，不会在 FastAPI 启动或用户上传图片时自动运行。当前仓库提供训练和部署代码，不附带训练好的垃圾识别权重，也没有实际训练得到的准确率。

## 启动推理与后端

根目录 `.env.example` 中的配置需要设置为进程环境变量，程序不会自动读取 `.env` 文件。Spring Boot 与 FastAPI 两个进程须设置相同的 `SMARTWASTE_VISION_INTERNAL_TOKEN`，至少 32 个字符；可使用密码生成器或 `python -c "import secrets; print(secrets.token_hex(32))"` 自行生成。

在 FastAPI 终端完成配置后启动：

```powershell
$env:SMARTWASTE_VISION_INTERNAL_TOKEN = '替换为自己的随机内部密钥，至少32个字符'
$env:SMARTWASTE_VISION_DEVICE = 'cpu'
.\.venv\Scripts\python.exe run.py
```

`SMARTWASTE_VISION_HOST` 默认 `127.0.0.1`，`SMARTWASTE_VISION_PORT` 默认 `8082`。默认权重路径始终相对于本模块目录；也可用 `SMARTWASTE_VISION_CHECKPOINT` 指定绝对路径。部署时不会下载预训练权重，只加载本项目训练得到的文件。更新权重后需要重启推理进程。默认单 worker，模型前向推理串行运行，同时处理中的上传/解码请求最多 4 个。

业务后端进程设置相同内部密钥，并将 `SMARTWASTE_VISION_URL` 设置为服务地址，默认 `http://127.0.0.1:8082`。按原项目方式启动 Spring Boot 后，从用户工作台进入“垃圾分类查询与拍照识别”。RAG 服务不参与图片预测，无需为拍照识别启动 RAG。

服务接口：

- `GET /internal/vision/health`：需要 `X-Vision-Token`，模型就绪返回 200，缺少或无法加载权重返回 503。此时识别接口同样返回“模型未就绪”，不会用随机参数生成结果。
- `POST /internal/vision/predict`：需要相同请求头，使用 multipart 的 `file` 字段上传静态 JPEG、PNG、WebP。请求须有 `Content-Length`，主后端会自动生成。服务在解析 multipart 之前鉴权并限制总请求大小，随后校验实际图片内容、大小及像素。

预测结果包含 `name`、`sortId`、`sort`、`confidence`、`uncertain`、`message`、`modelVersion` 和最多三个 `candidates`。主后端保留页面原有的 `name`、`sort`、`rate` 字段，补充数字置信度与候选结果。示例标签只训练四大类时，`name` 是类别名称，不会虚构成具体物品名。

上传文件默认上限 5MB、像素上限 2400 万；主后端与推理服务共用 `SMARTWASTE_VISION_MAX_BYTES`，浏览器预检查为 5MB。`SMARTWASTE_VISION_CONFIDENCE_THRESHOLD` 默认 0.65，低于阈值时页面标记“识别结果待确认”，建议重新拍摄或使用文字查询。该阈值需要结合实际模型调整；Softmax 分数不是校准后的真实正确概率，阈值也不能保证检测所有非垃圾或未知物品。当前实现为单张图片分类，不包含目标检测或多物品逐个识别。
