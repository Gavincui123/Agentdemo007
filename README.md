# Agentdemo007 — 电商智能客服 Agent

基于 **Spring Boot 4.1 + LangChain4j 1.19 + LangGraph4j 1.5** 构建的电商客服 Agent 单体应用：一条多阶段流水线完成「分诊 → 意图识别 → 路由 → 工具/RAG → 流式回答」，配合超时预算治理、主备模型容灾、断路器与 SSE 生命周期兜底，实现**外部依赖缺失时能力降级而非崩溃**（dev 默认零外部依赖可启动）。

> 调优实录见 [docs/blog/2026-09-16-agent-latency-stability-tuning.zh.md](docs/blog/2026-09-16-agent-latency-stability-tuning.zh.md)（[English](docs/blog/2026-09-16-agent-latency-stability-tuning.en.md)）：全链路延迟从 80.5s 降至 6.1~11.9s，规则路径首字 2.1s。

## 核心能力

| 能力 | 说明 |
|------|------|
| 多阶段流水线 | 关键词前置分诊（0 LLM）→ 会话摘要锚点 → 查询改写/意图识别（小模型）→ 路由分发 → 路由计划 → 工具执行/RAG → 上下文组装 → 主模型流式输出 → 回合收尾持久化 |
| 模型网关容灾 | 多 provider（如 SiliconFlow 主 / 阿里云备）故障转移、模型级熔断、超时预算治理（LLM 20s < SSE 120s）、LC4j 内部重试关闭不叠加 |
| SSE 流式输出 | Token 级流式 + 步骤进度推送 + 超时/错误兜底话术 + Stop 按钮协作取消 + 后端统计耗时/首字延迟 |
| 工具调用 | LangChain4j 原生 `@Tool`：订单/用户/商品查询（RUNTIME 通道）+ 退货/退款/促销政策（RAG 通道），自纠正迭代 + 失败话术兜底 |
| RAG 混合检索 | 稠密嵌入 + BM25 稀疏召回 + 重排，主备容灾；嵌入/重排全挂时逐级降级（BM25-only），不回退不一致向量空间 |
| 高风险工作流 | 退款 6 节点 LangGraph 固定子图：实体门澄清 → 审批（HITL）→ 执行 → 终态收口，状态机续跑/切换/放弃 |
| 分段式提示词 | SystemPromptAssembler 按片段组装系统提示词，支持 Nacos 远程源热更新，缺 key 自动回退本地默认 |
| 会话记忆 | 会话缓存（Redis 可选 / 内存兜底）+ 摘要锚点 + 多轮话题独立性规则，防意图漂移与话题缠绕 |
| 可观测性 | Egress 日志（模型/耗时/尝试次数）、响应 totalMs/firstTokenMs、Actuator metrics/prometheus、管理台与评测接口 |

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
- **测试**：948 个测试全绿（9 个烟测门控跳过，需真实 LLM 环境）

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
| `REDIS_ENABLED` / `RABBITMQ_ENABLED` | `false` | Redis 会话缓存 / MQ 异步持久化 |
| `WORKFLOW_ENABLED` | `false` | 装配退款高风险工作流子图 |
| `PROMPT_SOURCE` | `local` | 提示词片段来源（`nacos` = 远程热更新） |
| `PIPELINE_MODE` | `linear` | 编排模式（`linear` / `graph`） |
| `NACOS_SERVER_ADDR` 等 | — | Nacos 连接参数（provider 列表与密钥在其 dataId 中注入） |

完整清单见 [src/main/resources/application.yml](src/main/resources/application.yml)（含逐项注释）与 [DEPLOY.md](DEPLOY.md)。

## API 一览

| 端点 | 鉴权 | 说明 |
|------|------|------|
| `POST /chat` | — | 对话主入口（SSE 流式：token 增量 + 步骤进度 + 耗时统计） |
| `POST /eval/run` | `X-Eval-Token` | 评测集批量回归 |
| `/admin/**` | `X-Admin-Token` | 管理台 |
| `GET /api/obs/*` | `X-Admin-Token` | 可观测数据查询 |
| `/actuator/{health,metrics,prometheus}` | — | 健康检查与指标 |

## 项目结构

```
src/main/java/com/agentdemo007/
├── web/          # ChatController（SSE 生命周期/超时兜底/协作取消）、SseProgressEmitter
├── capability/   # 分诊/改写/意图/路由/计划/工具/RAG/HITL/工作流 各流水线步骤
├── gateway/      # UnifiedModelGateway：路由执行器、主备故障转移、熔断、流式处理
├── prompt/       # 分段式提示词片段 + local/nacos 双源
├── context/      # SystemPromptAssembler 上下文组装
├── session/      # 会话缓存与摘要锚点
├── persistence/  # JPA 持久化 + RabbitMQ 异步发布（dev Noop）
├── common/       # PipelineContext / Orchestrator / 降级场景枚举 / progress
├── langgraph/    # 图编排节点抽象
├── eval/ admin/ observability/ trace/ feedback/  # 评测、管理台、可观测
frontend/         # Vue 3 + Vite SPA（构建产物进后端 jar）
docs/blog/        # 调优技术博客（中英双语）
DEPLOY.md         # 单 jar 部署指南（prod MySQL/Redis/MQ 接入）
docker-compose.yml# 中间件本地编排（MySQL / pgvector 等）
```
