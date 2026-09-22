# Agentdemo007 — 电商智能客服 Agent

基于 **Spring Boot 4.1 + LangChain4j 1.19 + LangGraph4j 1.5** 构建的电商客服 Agent 单体应用：一条多阶段流水线完成「分诊 → 意图识别 → 路由 → 工具/RAG → 流式回答」，配合超时预算治理、主备模型容灾、断路器与 SSE 生命周期兜底，实现**外部依赖缺失时能力降级而非崩溃**（dev 默认零外部依赖可启动）。

> 调优实录见 [docs/blog/2026-09-16-agent-latency-stability-tuning.zh.md](docs/blog/2026-09-16-agent-latency-stability-tuning.zh.md)：全链路延迟从 80.5s 降至 6.1~11.9s，规则路径首字 2.1s。生产化硬化实录见 [docs/blog/2026-09-18-decision-arbiter-hitl-l2-access-gate.zh.md](docs/blog/2026-09-18-decision-arbiter-hitl-l2-access-gate.zh.md)：会话仲裁器、HITL L2 持久化、统一访问闸口。

## 核心能力

| 能力 | 说明 |
|------|------|
| 多阶段流水线 | 关键词前置分诊（0 LLM）→ 会话摘要锚点 → 查询改写/意图识别（小模型）→ 路由分发 → 路由计划 → 工具执行/RAG → 上下文组装 → 主模型流式输出 → 回合收尾持久化 |
| 模型网关容灾 | 多 provider（如 SiliconFlow 主 / 阿里云备）故障转移、模型级熔断、超时预算治理（LLM 20s < SSE 120s）、LC4j 内部重试关闭不叠加 |
| SSE 流式输出 | Token 级流式 + 步骤进度推送 + 超时/错误兜底话术 + Stop 按钮协作取消 + 后端统计耗时/首字延迟 |
| 工具调用 | LangChain4j 原生 `@Tool`：订单/用户/商品查询（RUNTIME 通道）+ 退货/退款/促销政策（RAG 通道），自纠正迭代 + 失败话术兜底 |
| RAG 混合检索 | 检索漏斗「宽召回 → 知识域窄化 → 宽松粗滤 → 条件重排（池 ≤ top_n 跳过）→ 置信度终闸」；真库 Chroma（稠密）+ **Lucene 磁盘倒排 BM25**（稀疏，启动流式同步、堆内存极小）；dev 内存种子零依赖启动；嵌入/重排全挂逐级降级，低置信知识绝不进 LLM |
| 知识库入库流水线 | 多格式文档（md/HTML/Word/PDF 文本/PDF 扫描件/Excel/CSV/JSON 活动规则/纯图片）分型解析 → 清洗 → 分层切分 → 幂等落库 Chroma/Milvus，见 [scripts/rag-ingest](scripts/rag-ingest/README.md) |
| 高风险工作流 | 退款/退货 LangGraph 固定子图：实体门澄清 → 审批（HITL）→ 执行 → 终态收口，状态机续跑/切换/放弃；检查点三级持久化（内存真相源 → Redis 热副本 24h → DB，写入全异步）+ 幂等键严格匹配 + 业务前置对账（退款查支付状态/退货查物流状态，fail-closed）+ 显式恢复接口 |
| 会话仲裁与能力契约 | routePlan 为本轮能力决策契约（澄清/工具/RAG 在此定死，下游只执行）；跨轮开放语义（衔接/撤销/绑定）由小模型仲裁器升级判定，任何失败恒回退确定性状态机；已提交售后单按 `{action}:{orderId}` 业务键记忆，防重复澄清/重复提单 |
| 访问闸口 | Nacos 统一旋钮热更新（`agentdemo-gate.json`）：`enabled=true` 一次性生效 /chat 口令 + 外部 IP 每日轮次限额（localhost 豁免）+ /eval 仅本机；配额 Redis 持久化（内存可降级），IP 解析 X-Real-IP 优先防伪造，口令常量时间比对 |
| 分段式提示词 | SystemPromptAssembler 按片段组装系统提示词，支持 Nacos 远程源热更新，缺 key 自动回退本地默认 |
| 会话记忆 | 会话缓存（Redis 可选 / 内存兜底）+ 摘要锚点 + 多轮话题独立性规则，防意图漂移与话题缠绕 |
| 可观测性 | Egress 日志（模型/耗时/尝试次数）、响应 totalMs/firstTokenMs、Actuator metrics/prometheus、管理台与评测接口 |

## 效果展示

以下截图均为本地实测（原图见 [src/main/resources/img/](src/main/resources/img/)）。

**多轮对话与意图路由**：算术 → 商品推荐 → 压力复读退货 → 商品咨询四轮连续会话——意图分类稳定不漂移、规则短路径首字 2.1s、回复附可追溯「参考来源」：

![多轮对话与意图路由](src/main/resources/img/1-意图识别优化后的效果.png)

**知识来源引用（citations）**：RAG 回答附来源片段列表，「可追溯不等于绝对正确」：

![知识来源引用](src/main/resources/img/带知识来源citation的回答.png)

**HITL 提交制（审批是事件、Agent 最小权限）**：高风险售后操作由 LLM 三态资格裁决后建 PENDING 工单进管理台即返回，Agent 只有提交权没有执行权；管理员批准触发业务系统执行（mock），客户经工单查询工具读进度：

![HITL 工单审批](src/main/resources/img/HITL-高风险Agent只有提交权没有执行权.png)

**审批后的状态验证**：客户问「ORD-001 退货状态」，工具如实转述工单四态与业务执行结果：

![HITL 状态验证](src/main/resources/img/HITL-高风险操作通过后的状态验证.png)

**内部可观测**：每次 LLM 调用留痕 `scene / model / durMs / attempt`，System prompt 分段组装、RAG 漏斗逐段明细全程 DEBUG 可查：

![内部日志](src/main/resources/img/意图识别部分日志内容.png)

**全链路遥测仪表盘**（/obs 页）：16 字段遥测快照——流量读数（对话请求/总轮次/降级占比）、结局分布与 RAG 命中双环图、工具失败/HITL 触发/故障转移耗尽/待审批工单等信号读数：

![全链路遥测快照](src/main/resources/img/可观测性-全链路遥测快照.png)

## 架构总览

```
用户 ──SSE──▶ ChatController
                │
                ▼
        PipelineOrchestrator（linear / graph 两种编排，按 app.pipeline.mode 切换）
                │
  ┌─────────────┼──────────────────────────────────────────────┐
  ▼             ▼                                              ▼
①KeywordTriageStep（规则·0 LLM）  ⑥ToolExecutionStep（LC4j @Tool）  UnifiedModelGateway
②SessionSummaryAnchorStep        ⑦SystemPromptAssembler              │ 主备故障转移
③QueryRewrite + IntentStep       ⑧OutputStep（SSE 流式）              │ 模型级熔断
④RouteStep（路由分发）            ⑨ChatTurnFinalizer（会话持久化）      │ 超时预算 20s
⑤RoutePlanStep（路由计划）        RagStep（混合检索+降级）              ▼
                                              SiliconFlow ─▶ Aliyun ─▶ 场景兜底话术
```

外部依赖（LLM / Redis / RabbitMQ / Nacos / MySQL）任一缺失时程序仍可启动响应，各层自动降级。

## 技术栈

- **后端**：Java 17、Spring Boot 4.1.1、LangChain4j 1.19.0（OpenAI 兼容 + 工具调用）、LangGraph4j 1.5.14（图编排/工作流子图）
- **中间件**：Nacos 3.x（配置中心 + 提示词远程源）、Redis（会话缓存）、RabbitMQ（异步持久化 + DLQ）、JPA（dev H2 / prod MySQL）
- **前端**：Vue 3 + Vite + TypeScript（hash 路由 SPA，构建直出后端 `classpath:/static/`，单 jar 托管）
- **测试**：1157 个测试全绿（11 个真库烟测门控跳过，需真实密钥环境）

## 快速开始

### 前置要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17 | 唯一硬性要求 |
| Node.js | ≥ 20.19 | 仅前端构建期需要 |
| Maven | 无需安装 | 项目自带 `mvnw` |

### 1. 构建

```bash
# 前端构建（产物直出后端 classpath:/static/，跳过则页面为空）
cd frontend && npm install && npm run build && cd ..

# 后端打包（含前端静态资源，单 jar 即整站）
./mvnw clean package
```

### 2. 运行

```bash
# dev 默认：零外部依赖可起（H2 内存库 / 内存会话缓存 / Noop LLM 不调真实引擎）
./mvnw spring-boot:run

# 接入真实 LLM（OpenAI 兼容 provider 经 Nacos dataId 注入，密钥只走环境变量）
LLM_ENABLED=true \
NACOS_SERVER_ADDR=<nacos-host>:8848 \
./mvnw spring-boot:run
```

打开 http://localhost:8080 即为客服对话页（`/#/admin` 为管理台）。

### 3. 测试

```bash
./mvnw test                              # 全量
./mvnw -Dtest=RoutePlanTest test         # 单个测试类
```

## 关键配置（环境变量）

密钥与连接参数一律经环境变量注入，配置文件仅 `${}` 占位、不落明文。常用项：

| 环境变量 | 默认 | 说明 |
|----------|------|------|
| `LLM_ENABLED` | `false` | 接入真实 LLM 网关（false = Noop 降级执行器） |
| `LLM_TIMEOUT` | `20s` | 单次 LLM 调用超时（须远小于 SSE 120s 超时） |
| `LLM_THINKING_ENABLED` | `false` | 思考模式（开启需同步调大 `LLM_MAX_TOKENS`） |
| `EMBEDDING_ENABLED` / `RERANKER_ENABLED` | `false` | 稠密嵌入 / 远程重排（关闭走本地降级实现） |
| `VECTORSTORE_TYPE` | `inmemory` | 向量库 `inmemory`（种子语料）/ `chroma`（真实 RAG 真库，须开 `EMBEDDING_ENABLED` 且模型与入库一致） |
| `CHROMA_BASE_URL` / `CHROMA_COLLECTION` / `CHROMA_TOKEN` | `http://localhost:8001` 等 | Chroma v2 连接（地址 / 集合名 / 可选 Bearer） |
| `RAG_RECALL_DENSE` / `RAG_RECALL_SPARSE` | `24` | 检索漏斗·稠密/稀疏召回预算（宽召回，生产大库调 50+） |
| `RAG_PREFILTER_MIN_SCORE` / `RAG_RERANK_TOP_N` | `0.2` / `10` | 检索漏斗·重排前宽松粗滤（0=关）/ 重排 top_n = 注入上限（池 ≤ top_n 跳过重排） |
| `RAG_MIN_SCORE` / `RAG_RERANK_MIN_SCORE` / `RAG_MIN_COUNT` | `0.3` / `0.3` / `1` | 置信度终闸：未重排 cosine 阈值（真库建议 0.4）/ 重排 relevance 阈值 / 数量下限（不足→RAG_SKIP） |
| `LUCENE_INDEX_DIR` | `./data/lucene-bm25` | 真库模式 Lucene BM25 磁盘索引目录（启动时从 Chroma 流式同步） |
| `REDIS_ENABLED` / `RABBITMQ_ENABLED` | `false` | Redis 会话缓存 / MQ 异步持久化 |
| `GATE_ENABLED` / `GATE_ACCESS_CODE` / `GATE_DAILY_LIMIT` | `false` / — / `5` | 访问闸口本地兜底值（发布口径以 Nacos `agentdemo-gate.json` 热更新为准）：`true` 一次性生效 /chat 口令 + 外部 IP 每日轮次（localhost 豁免）+ /eval 仅本机 |
| `HITL_CHECKPOINT_REDIS_TTL` | `24h` | 挂起检查点 Redis 热副本 TTL（`REDIS_ENABLED=true` 时双写；DB 恒为持久真相源） |
| `WORKFLOW_ENABLED` | `false` | 装配售后高风险工作流子图（**提交制·2026-09-20 原则：审批是事件、Agent 最小权限**——Agent 资格裁决（政策知识+订单实时事实交 LLM 三态裁决）后建 PENDING 工单进管理台 `/admin/hitl/tickets` 即返回，**无请求内等待**；决议=工单状态变更事件，批准触发业务系统执行 mock；客户经工单查询工具读进度；`wfa:{action}:{orderId}` 幂等键，业务门对账照常） |
| `REFUSAL_MODE` | `prompt` | 拒答机制：`off` / `prompt`（RAG 无依据→提示词强约束，工具无数据→如实告知框定）/ `strict`（知识 grounding 未命中且证据通道全空→零 LLM 短路拒答话术；生产建议） |
| `KB_CHUNK_MAX_CHARS` / `KB_CHUNK_OVERLAP` / `KB_PREVIEW_LIMIT` | `500` / `80` / `50` | Java 侧知识库录入切块参数（通用递归切块；条款型文档自动切条款感知模式） |
| `PROMPT_SOURCE` | `local` | 提示词片段来源（`nacos` = 远程热更新） |
| `PIPELINE_MODE` | `linear` | 编排模式（`linear` / `graph`） |
| `NACOS_SERVER_ADDR` 等 | — | Nacos 连接参数（provider 列表与密钥在其 dataId 中注入） |

完整清单见 [src/main/resources/application.yml](src/main/resources/application.yml)（含逐项注释）与 [DEPLOY.md](DEPLOY.md)。

## API 一览

| 端点 | 鉴权 | 说明 |
|------|------|------|
| `POST /chat` / `POST /chat/stream` | 闸口开启时需访问口令 | 对话主入口（SSE 流式：token 增量 + 步骤进度 + 耗时统计） |
| `POST /eval/run` / `GET /eval/progress` | `X-Eval-Token`（闸口开启时仅限本机） | 评测批量回归（异步作业秒回 + 进度轮询） |
| `POST /api/gate/login` / `GET /api/gate/status` | — | 访问闸口登录与状态查询（额度展示） |
| `/admin/**` | `X-Admin-Token` | 管理台（含 `POST /admin/hitl/tickets/{id}/confirm·reject·resume` 人工审批/恢复） |
| `/admin/kb/*` | `X-Admin-Token` | 知识库录入管理：`POST /admin/kb/preview`（解析/清洗/切块试运行，不入库）、`POST /admin/kb/documents`（正式录入+版本收口）、`GET /admin/kb/documents[/{id}]`（台账/详情）、`DELETE /admin/kb/documents/{id}`（下架） |
| `GET /api/obs/*` | `X-Admin-Token` | 可观测数据查询 |
| `/actuator/{health,metrics,prometheus}` | — | 健康检查与指标 |

## 知识库语料与入库（RAG 前置）

### Java 侧在线录入（/admin/kb + 前端「知识库」页）

运行期在线录入通道（与下方 Python 批量流水线互补）：管理台前端「知识库」页或 `/admin/kb/*` API 上传文档 → **多格式解析**（md/txt、html、pdf、docx、xlsx、csv/tsv、json，POI/PDFBox/jsoup）→ **清洗**（NFKC、页码行/零宽字符/控制字符剔除、空白规整）→ **切块**（通用递归切块：段落打包+超长句级递归切分带 overlap；**专业领域条款感知**：检测「第X条 / 1.2 /（一）」编号 ≥3 处自动切条款模式，条款原子不拦腰切、超长条款带「（承接第X条）」锚点，面包屑标题路径随块入库）→ 嵌入入库（复用 VectorStore seam，dev 内存 / 真库 Chroma 同路）。

**文档元数据与治理**（`kb_document` / `kb_chunk` 两表）：

- **版本管理**：同「命名空间+文档号」重灌即升版本——旧版 SUPERSEDED 且其片段按精确 source 清单从稠密（Chroma/内存）与稀疏（Lucene）通道同步删除，检索只见最新版；DB 保留全版本历史
- **命名空间**：`PUBLIC`（人人可检索）/ `PRIVATE`（仅 `allowedPrincipals` 名单主体，对话侧取 `PipelineContext.userId`）
- **权限过滤**：`KbCatalogService` 内存快照在 RagStep 漏斗入口按主体过滤（种子/Python 流水线旧语料不带 `kb:` 前缀，按公开语义向后兼容恒放行）
- **试运行**：`dryRun` 解析/清洗/切块预览不入库，确认后正式录入

### 拒答机制（无依据不编造）

跨分支收口：① **RAG 分支**——漏斗终态零片段时置位 `groundingMiss`，`RefusalGateStep@690` 按 `REFUSAL_MODE` 裁决（strict 零 LLM 短路拒答话术 / prompt 注入 System Runtime 块「严禁自身知识补答」强约束）；② **工具分支**——工具连通但未命中数据（订单不存在/政策 `hit=false`）路由独立「工具无数据」通道，框定终答必须如实告知未查到、禁止拿相近数据顶替；③ **提示词层**——默认系统提示词含拒答边界（依据不足必须如实说明，禁止模型自身知识补业务口径）。拒答是业务级正确行为，不与降级语义混淆（不标 degraded），strict 短路写 REFUSAL 审计事件。

真实 RAG 的语料与批量入库流水线已独立落地，与 Java 检索侧通过「同嵌入模型 + 元数据契约」对齐：

- **语料**：[src/main/resources/corpus/](src/main/resources/corpus/) — 23 份客服知识文档（20 份政策/FAQ Markdown + 3 份 JSON 活动规则），入库后 **155 块**。活动规则带时间窗口（已结束/进行中/未开始三种状态并存），用于验证时效处理效果
- **入库流水线**：[scripts/rag-ingest/](scripts/rag-ingest/README.md) — 纯 Python 四层流水线：**分型解析 → 清洗 → 分层切分 → 统一落库**
  - 10 类文档分型解析：md/txt 直读、HTML、Word（标题样式+表格按文档序）、PDF 文本型（版面块）、PDF 扫描件（OCR 可选、缺依赖降级占位）、Excel/CSV（表头钉住、大表分块带头）、JSON 活动规则（一条记录一个原子块，时间窗口写入 `valid_from/valid_until` 元数据）、纯图片（OCR 可选）
  - 切分：结构优先（标题层级 + 节标题前缀增强嵌入）、语义断点、父子块（小块检索大块返回）、表格永不跨块，兜底 600 字重叠切
  - 幂等：确定性 chunk id（sha256）+ 嵌入磁盘缓存（语料未变 0 次 API 调用）；`--rebuild` 重建集合防删除后孤儿块
- **端到端命令**：

```bash
cd scripts/rag-ingest
python3 -m venv .venv && source .venv/bin/activate && hash -r   # 一律 python3（多 Python 共存陷阱见教程 §5.2）
pip install -r requirements.txt
export SF_KEY=<SiliconFlow API Key>
python3 ingest.py --input ../../src/main/resources/corpus \
  --store chroma --uri http://<chroma-host>:8001 \
  --parent-child --rebuild
```

- **向量空间一致性**：嵌入模型必须与 Java 检索侧完全一致（SiliconFlow `Qwen/Qwen3-Embedding-8B`，4096 维），换模型需全量重灌
- **教程**（10 类文档分型矩阵 / 切分策略选型 / OCR 对比 / 远程 Chroma 连接与排障）：[docs/guides/rag-corpus-ingestion-tutorial.md](docs/guides/rag-corpus-ingestion-tutorial.md)

## 项目结构

```
src/main/java/com/agentdemo007/
├── web/          # ChatController（SSE 生命周期/超时兜底/协作取消）、SseProgressEmitter
├── capability/   # 分诊/改写/意图/路由/计划/工具/RAG/HITL/工作流 各流水线步骤
├── gate/         # 访问闸口：统一旋钮（Nacos 热更新）+ 口令 + IP 日额（Redis/内存配额）
├── gateway/      # UnifiedModelGateway：路由执行器、主备故障转移、熔断、流式处理
├── prompt/       # 分段式提示词片段 + local/nacos 双源
├── context/      # SystemPromptAssembler 上下文组装
├── session/      # 会话缓存与摘要锚点
├── persistence/  # JPA 持久化 + RabbitMQ 异步发布（dev Noop）
├── common/       # PipelineContext / Orchestrator / 降级场景枚举 / progress
├── langgraph/    # 图编排节点抽象
├── eval/ admin/ observability/ trace/ feedback/  # 评测、管理台、可观测
frontend/         # Vue 3 + Vite SPA（构建产物进后端 jar）
src/main/resources/corpus/   # RAG 知识库语料（23 份：政策/FAQ Markdown + 活动规则 JSON）
scripts/rag-ingest/          # 多格式文档清洗切分入库流水线（Python）
docs/              # 文档（分类管理，导航见 docs/README.md）
├── architecture/  # 架构全景 + 七层架构总图
├── design/        # 专项设计规格（specs）与 TDD 实现计划（plans）
├── guides/        # 实操指南：部署实录 / 语料入库教程
├── reports/       # 交付报告与实验记录
└── blog/          # 十二章工程实录系列（每章配 SVG 图 + 真实代码，多数章节另附 mermaid 图）
DEPLOY.md          # 单 jar 部署指南（prod MySQL/Redis/MQ 接入 + 闸口发布清单）
docker-compose.yml# 中间件本地编排（MySQL / pgvector 等）
```

## 文档地图

> 完整导航见 [docs/README.md](docs/README.md)（文档按 architecture / design / guides / reports / blog 分类管理）。

**总览与计划**

| 文档 | 内容 |
|------|------|
| [docs/DEVELOPMENT-PLAN.md](docs/DEVELOPMENT-PLAN.md) | 22 个 Phase 的工程化开发计划与实现注记（每处与原设计偏差的诚实记录） |
| [DEPLOY.md](DEPLOY.md) | 部署手册：环境变量大全、Nacos dataId 示例、闸口发布清单 |

**架构（docs/architecture/）**

| 文档 | 内容 |
|------|------|
| [docs/architecture/项目全景详解.md](docs/architecture/项目全景详解.md) | **从这开始读**：15 章全景——架构、流水线步骤、意图识别/多轮对话踩坑实录（漂移/缠绕/粘性/模糊澄清/仲裁器）、RAG 漏斗、HITL L2、闸口、关键数字速查 |
| [docs/architecture/框架文件.md](docs/architecture/框架文件.md) | 七层架构总图 |

**实操指南（docs/guides/）**

| 文档 | 内容 |
|------|------|
| [docs/guides/部署实录.md](docs/guides/部署实录.md) | 首次上线的踩坑手册：profile/dataId/容器网络/MySQL 授权/nginx 接入等 15+ 个真实问题的现象→根因→修复，含更新回滚流程与安全清单 |
| [docs/guides/rag-corpus-ingestion-tutorial.md](docs/guides/rag-corpus-ingestion-tutorial.md) | 多格式语料「清洗-切分-入库」教程（10 类文档分型/幂等/嵌入缓存/坑） |

**报告与实验（docs/reports/）**

| 文档 | 内容 |
|------|------|
| [docs/reports/2026-09-19-refusal-kb-ingest-report.md](docs/reports/2026-09-19-refusal-kb-ingest-report.md) | 拒答机制 + 知识库录入交付报告（含「全绿≠无缺陷」缺陷清单） |
| [docs/reports/rag-redteam-conflict-demo.md](docs/reports/rag-redteam-conflict-demo.md) | RAG 红队演示：毒片实验与防线盲区诚实标注 |

**设计规格与实现计划（docs/design/）**

| 文档 | 内容 |
|------|------|
| [docs/design/](docs/design/)（specs ×3 · plans ×4） | 专项设计规格与 TDD 实现计划（工作流 DAG / 分段提示词 / P0 意图切换 / 前端设计） |

**博客实录（docs/blog/）—— 系列《Agentdemo007 工程实录》十二章（[系列目录](docs/blog/README.md)：设计篇 1-2 → 攻坚篇 3-9 → 交付篇 10-12）**

| 文档 | 内容 |
|------|------|
| [第一章：设计理念与总体架构](docs/blog/2026-09-21-design-philosophy-architecture.zh.md) | 七层蓝图、15 步流水线、四态出口、选型答辩 |
| [第二章：提示词工程体系](docs/blog/2026-09-21-prompt-engineering-system.zh.md) | 分段装配、防漂移、注入位纪律、双源热更 |
| [第三章：模型网关与韧性治理](docs/blog/2026-09-21-model-gateway-resilience.zh.md) | 异常分诊四层、重试归属地、熔断阈值光谱、有界 Agent loop |
| [第四章：会话记忆分层](docs/blog/2026-09-21-session-memory-layering.zh.md) | L1 窗口/L2 滚动摘要/L3 画像、读-改-写竞态收口、注入位隔离与读路径三道防线 |
| [第五章：检索侧演进](docs/blog/2026-09-21-rag-evolution-abac-refusal.zh.md) | Hybrid 融合、时效治理、知识分级 ABAC、拒答四层与红队 |
| [第六章：决策层/持久化/闸门硬化](docs/blog/2026-09-18-decision-arbiter-hitl-l2-access-gate.zh.md) | 会话仲裁器、HITL L2 持久化、统一访问闸口 |
| [第七章：业务工具与售后工作流 DAG](docs/blog/2026-09-21-business-tools-workflow-dag.zh.md) | 方向纠偏、mock↔DB 对账对齐、审批=事件、建单幂等四态 |
| [第八章：全链路延迟与稳定性调优](docs/blog/2026-09-16-agent-latency-stability-tuning.zh.md)（[EN](docs/blog/2026-09-16-agent-latency-stability-tuning.en.md)） | 80.5s → 6.1s 实录：超时预算治理、SSE 生命周期、意图漂移修复 |
| [第九章：评测体系](docs/blog/2026-09-21-eval-harness-hollow-eval.zh.md) | hollow eval 事故、评测异步化、黄金集双源、「全绿≠无缺陷」 |
| [第十章：前端与流式交互](docs/blog/2026-09-21-frontend-streaming-ux.zh.md) | 单 jar 里的 SPA、手撸 SSE 协议、权威终态收口 |
| [第十一章：可观测性与审计](docs/blog/2026-09-21-observability-audit-trace.zh.md) | 三条正交信道、append-only 审计链、「空白即线索」 |
| [第十二章：部署交付实录](docs/blog/2026-09-21-deploy-delivery-hardening.zh.md) | 一进程即整站、部署翻车九连、公网安全清单、回滚即换指针 |

**其他**

| 文档 | 内容 |
|------|------|
| [src/main/resources/sql/](src/main/resources/sql/) | HITL L2 三表 DDL + mock 数据（用户自建） |
| [scripts/rag-ingest/](scripts/rag-ingest/README.md) | Python 批量入库流水线（10 类文档分型解析 → 清洗 → 切分 → 幂等落库） |
