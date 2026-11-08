# RAG 客服本地运行

业务主服务保留 Spring Boot 2 / Java 8 编译目标。`rag-service` 独立使用 Java 17、Spring Boot 3.5 和 Spring AI 1.0，通过受内部密钥保护的 HTTP 接口接入主服务；无需升级原有业务代码。两个服务分别构建、启动，根目录的 Maven 构建不会构建 RAG 服务。

1. 配置根目录 `.env.example` 中的 RAG、Elasticsearch、Chroma、模型和精排参数到**进程环境变量**。Spring Boot 不自动读取 `.env` 文件。
2. `SMARTWASTE_RAG_INTERNAL_TOKEN` 在两个进程中设置相同的随机值，至少 32 个字符。RAG 默认仅监听 `127.0.0.1:8081`；跨容器运行时自行配置绑定地址及内网访问限制，主服务设置 `SMARTWASTE_RAG_URL`。
3. 准备 Elasticsearch 8.x 与支持 v2 API 的 Chroma 1.x 服务。服务自动创建独立的 ES 索引和 Chroma 集合，中文采用 ES 内置 `cjk` 分析器。修改向量模型或维度时，使用新的 Chroma 集合和 ES 索引名称，逐篇重建索引，全部成功后再恢复用户访问。
4. 聊天与向量模型使用 Spring AI OpenAI 兼容接口，`BASE_URL` 不带 `/v1`。可单独设置向量服务地址与密钥。精排地址是完整的 `/v1/rerank` 地址，请使用返回 `results[].index` 和 `results[].relevance_score` 的 Jina/Cohere 兼容服务；分数阈值需结合实际模型调整。
5. `SMARTWASTE_QUERY_MODEL` 专门负责查询改写及多查询扩展，`SMARTWASTE_CHAT_MODEL` 负责最终回答，两者独立配置。查询模型默认使用 `gpt-4o-mini`，回答模型沿用原配置；如需使用能力更强的回答模型，请另行设置 `SMARTWASTE_CHAT_MODEL`。查询服务地址和密钥默认继承 `SMARTWASTE_AI_BASE_URL` / `SMARTWASTE_AI_API_KEY`，也可通过 `SMARTWASTE_QUERY_BASE_URL` / `SMARTWASTE_QUERY_API_KEY` 配置独立提供方。仅支持相应提供方实际开放的模型，不会自动下载或部署模型。

在项目根目录构建、启动主服务：

```powershell
mvn '-Dmaven.test.skip=true' package
java -jar target/smart-waste-0.0.1-SNAPSHOT.jar
```

另一个终端使用 JDK 17，在 `rag-service` 目录构建、启动：

```powershell
mvn '-Dmaven.test.skip=true' package
java -jar target/smartwaste-rag-0.0.1-SNAPSHOT.jar
```

管理员登录后，从“客服知识库”进入 `/workflow/knowledge.html`。可以新增或编辑知识、导入 UTF-8 Markdown/文本、删除文档和重建单篇索引。首次点击“导入内置示例”导入四篇文档，该操作会调用向量服务；已有同来源示例会跳过，部分失败后可以重试。示例不是地方分类标准，上线前应使用当地正式资料及实际设备手册替换。

用户登录后从投放工作台进入 `/workflow/chat.html`。页面支持知识类别筛选、最多 8 条上下文和来源原文展开。调用链为轻量模型一次查询规划 → 多查询 BM25 / Chroma 召回 → 加权 RRF（k=60）→ Reranker → 独立回答模型生成答案。查询规划输出独立主问题与有差异的检索表述；原问题始终保留在第一位，默认连同改写和扩展最多 5 条，简单问题允许更少。主问题用于消解指代和统一精排，最终回答依据原始提问、必要历史及知识原文生成。查询扩展仅优化检索，不作为事实依据。

检索使用一次 ES `_msearch`、一批查询向量化及一次 Chroma 批量查询，默认每个查询每路取 Top 10，最多约 100 条候选。按分块 ID 去重并做加权 RRF，原问题权重为 1，其余查询权重总和为 1，防止扩展数量增加后压过原问题。融合后最多 40 条统一精排，最终最多 6 条用于生成，可通过 `SMARTWASTE_RETRIEVAL_TOP_K`、`SMARTWASTE_RETRIEVAL_CANDIDATES`、`SMARTWASTE_RETRIEVAL_EVIDENCE` 调整。实际精排还会应用相关性阈值，最终证据可能更少。

`SMARTWASTE_QUERY_MAX_QUERIES` 包含原问题，范围为 1～5；`SMARTWASTE_QUERY_TIMEOUT_MILLIS` 默认 15000，控制单次查询模型 HTTP 读取超时，连接超时不超过 3000 毫秒；该模型不自动重试。查询模型未配置、超时或输出 JSON 无效时，会提示并退回原问题检索，不调用回答模型补做改写。`SMARTWASTE_QUERY_ENABLED=false` 可主动关闭查询优化。任一召回服务不可用时使用另一条链路；批量召回部分失败时保留可用结果。未配置或不可用的精排服务降级到 RRF。两路均未找到资料时拒绝猜测；生成失败或引用编号无效时展示知识原文，明确标识为“知识原文”。接口保留 `retrievalQuery` 表示独立主问题，并新增 `retrievalQueries` 返回本次参与召回的全部查询。

知识原文与有效分块版本持久化在 `SMARTWASTE_RAG_DATA_DIR`，采用单实例文件存储。只有两个检索索引都写入成功才发布新版本；检索使用已发布分块白名单，删除或替换后残留的旧分块不会被引用。请同时备份原文目录和两个索引，并将原文目录配置为持久化卷。本地示例适用于单实例，不支持多个 RAG 进程同时写同一目录。索引写入或清理异常时页面会提示，日志不输出模型密钥与正文。健康页检查索引连接，并展示查询优化、精排是否配置；“已配置”仅说明配置齐全，不能代替模型与精排的实际联调。

接口：用户 `POST /rag/chat`；管理员 `/rag/admin/documents` 的增删改查、`POST /rag/admin/documents/{id}/reindex`、`POST /rag/admin/seed`、`GET /rag/admin/status`。浏览器只访问主服务，由原有双令牌鉴权识别角色；内部密钥及模型配置不会发送给浏览器。
