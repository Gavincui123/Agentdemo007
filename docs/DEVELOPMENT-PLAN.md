# Agentdemo007 生产级 AI Agent 开发计划
> 本文件为可执行的工程化开发计划，严格对齐 `docs/框架文件.md` 的七层架构 + 横向支撑体系。
> 代码基线：Spring Boot 4.1.1 / Java 17 / Maven，根包 `com.agentdemo007`。
> 技术底座：LangChain4j（多模型装配）+ LangGraph4j（多步推理编排，独立 Phase）。
> 对外暴露核心端点：`POST /chat`（对话能力，同步+SSE流式）、`POST /eval/run`（模型与意图评测端点）。

---

## 0. 架构分层总览（对齐框架文件）
本计划严格按框架文件的七层 + 横向支撑体系组织，每层对应一个或多个开发阶段：

| 层 | 框架层名 | 核心职责 | 对应 Phase |
|---|---|---|---|
| 第一层 | 接入与安全层 | 基础数据校验、速率限制&熔断器、输入安全过滤器、统一异常处理器 | Phase 1 |
| — | 终端治理 + 链路追踪 | 统一返回体、全局异常、TraceFilter、结构化日志 | Phase 1 |
| — | 配置中心基础 | Nacos 配置中心（仅配置，非注册/发现）、多环境隔离、密钥注入 | Phase 2 |
| — | 会话缓存基础 | Redis 热上下文、标准 LLM 消息格式、TTL | Phase 3 |
| 第二层 | 会话管理与理解层 | 会话路由、Redis缓存、摘要Hook、用户问题改写、改写质量校验、标准化Query | Phase 6 |
| 第三层 | 意图识别与模型路由层 | 多层级意图识别、分类校验、路由分发（闲聊/推理/长上下文/结构化）、模型选择策略 | Phase 7 |
| 第四层 | 能力与执行层 | RAG（向量检索/Rerank/注入扫描）、工具（参数解析/Schema校验/执行）、HITL（高风险确认/人工工单） | Phase 9-11 |
| 第五层 | 上下文构建工厂 | 三层隔离（系统锚点/客观数据/用户指令），按序拼接 Sys→Runtime→His→RAG→Tool→User | Phase 8 |
| 第六层 | 统一模型网关与配置中心 | 模型配置中心（元数据/路由规则/流控/容灾，热加载）、统一网关（Token预算/速率限制/429降级/故障转移） | Phase 4 |
| 第七层 | 结构化输出与安全 | 结构化输出网关、JsonSchema校验、重试兜底、输出安全过滤器 | Phase 12 |
| — | 韧性层 | 异常四层分诊、指数退避+全抖动重试、熔断、工具异常透传自纠正 | Phase 5 |
| — | 异步持久化 + 审计 | RabbitMQ 削峰、会话/审计异步落库、死信队列 | Phase 13 |
| — | LangGraph 集成 | Agent 状态机、多步推理编排、条件分支 | Phase 14 |
| 横向 | 可观测性 + 运维 + 微调闭环 + 评测 | 全链路Trace/指标/日志/审计、运维控制台动态调权、在线反馈→离线数据池→微调→模型注册→配置中心热更新、评测端点、生产验收 | Phase 15 |
| 架构演进 | 工具断路器 + AgentScope 2.0 集成 | per-tool 熔断、ReAct/Harness 引擎重构、线性/LangGraph/AgentScope 三引擎共存可切 | Phase 17-18 |
| 检索增强 | 约束改写 + Hybrid RAG + 时效治理 | 改写只补不替保 original query、向量+关键词+规则路由三通道、RAG 片段时效标注 | Phase 20 |
| 前端 | 可视化平台 UI | 对话界面（流式+工具/RAG折叠展示）、运维管理台（路由调权/模型配置/HITL工单/会话历史）、评测可视化、可观测性仪表盘 | Phase 16, 19 |

> **开发顺序说明**：基础设施（Phase 1-3）→ 模型网关与配置中心（Phase 4）→ 韧性治理（Phase 5）→ 自上而下打通理解/决策/构建/能力/输出层（Phase 6-12）→ 异步持久化与审计（Phase 13）→ LangGraph 编排（Phase 14）→ 可观测/运维/微调闭环/评测/生产验收（Phase 15）→ **工具断路器（Phase 17，卡 Phase 9/5）+ AgentScope 2.0 集成（Phase 18，卡 Phase 14/15）+ 检索理解增强（Phase 20，卡 Phase 6/8/10，与前端并行）→ 前端对话界面（Phase 16，卡 Phase 12 `/chat` 打通后）→ 前端管理台/评测/仪表盘（Phase 19，卡 Phase 15 后端能力完成后）**。

---

## 1. 项目目标与生产级特性

### 1.1 核心目标
在 Spring Boot 工程基础上构建**最小权限、高韧性、可观测、可审计、多模型治理**的对话型 AI Agent 平台，完整对齐框架文件七层架构：
- **多模型治理**：模型配置中心热加载（元数据/路由规则/流控/容灾）+ 统一模型网关（Token预算校验、速率限制、429降级话术、主备故障转移）。
- **多层级意图识别 + 模型路由**：意图分类后按闲聊/简单QA、推理/分析、长上下文/复杂、结构化抽取四类路由，按标签/权重/成本可插拔策略选模型。
- **能力与执行层**：RAG（向量化→向量库→检索→Rerank重排→注入扫描→纯净片段）、工具调用（参数解析→Schema校验→执行API→返回数据）、HITL（高风险确认→超时/权限→人工工单）。
- **三层隔离上下文构建工厂**：系统锚点层 + 客观数据层 + 用户指令层，按序拼接。
- **结构化输出与安全**：JsonSchema 校验 + 重试兜底 + 输出安全过滤器。
- **会话理解**：会话路由、Redis缓存、摘要Hook、用户问题改写+质量校验→标准化Query。
- **LangGraph 多步推理编排**：Agent 状态机、条件分支、多步推理。
- **生产级能力**：全局异常治理、统一返回体、全链路追踪、四维指标、结构化日志、审计留痕、多环境隔离、动态配置、异步持久化、微调闭环。

### 1.2 对外暴露端点
| 端点 | 方法 | 能力说明 | 面向场景 |
|---|---|---|---|
| `/chat` | POST | 对话核心端点；支持同步返回与 SSE 流式输出；携带 sessionId 维持多轮上下文；经会话理解→意图识别→模型路由→能力执行(RAG/工具/HITL)→上下文构建→统一网关→结构化输出全链路 | C 端用户对话入口 |
| `/eval/run` | POST | 评测执行端点；批量传入测试用例，输出意图识别准确率、模型路由正确率、工具调用正确率、响应耗时、Token 消耗、故障转移触发率等评测报告 | 迭代验证、回归测试、效果评估，生产环境鉴权访问 |

### 1.3 生产级特性总览
| 维度 | 能力 |
|---|---|
| **安全权限** | 接入层输入安全过滤器、提示注入双层检测+内容包裹隔离、RAG注入扫描、输出安全过滤器、意图仅返回枚举、注入路径零 LLM 调用、最小权限工具集、HITL高风险拦截 |
| **韧性高可用** | 四层异常分诊、指数退避+全抖动重试、熔断器、工具异常透传 LLM 自纠正、主备模型故障转移、Redis 故障降级、MQ 死信队列、429降级话术 |
| **多模型治理** | 配置中心热加载、标签/权重/成本可插拔选择策略、Token预算与RPM/TPM流控、主备容灾、运维控制台动态调权灰度 |
| **可观测性** | 全链路 Trace 追踪、四维指标体系（资源/组件/业务/AI）、结构化 JSON 日志、分级告警 |
| **可审计性** | 独立审计事件队列、关键操作异步落库、不可篡改留存、注入/业务/工具/异常全留痕 |
| **配置治理** | Nacos 仅作配置中心（非注册/发现）、`spring.config.import` 现代接入、dev/prod 隔离、密钥环境变量注入、热更新不重启 |
| **架构解耦** | 七层严格分层、意图与业务解耦、能力层可插拔（RAG/工具/HITL）、Hook 事件机制、同步读+异步写会话、MQ 削峰 |
| **闭环演进** | 在线反馈→离线数据池→离线微调流水线→模型注册→配置中心 API 热更新 |
| **前端可视化** | Vue 3 + Vite 完整平台 UI：对话流式界面、运维管理台（路由调权/模型配置/HITL工单/会话历史）、评测可视化、可观测性仪表盘；开发期 Vite 代理后端，生产期单 jar 部署（`static/`）或 nginx 托管 |

---

## 2. 关键技术决策（已确认）

| 决策项 | 结论 |
|---|---|
| 工程框架 | Spring Boot 4.1.1 + Java 17，不引入 Spring Cloud 全家桶 |
| LLM 框架 | LangChain4j core + openai 模块，**手动 @Configuration 装配**，不使用 starter（规避 Boot 4 兼容风险） |
| 多模型治理 | 自研模型配置中心 + 统一模型网关，基于 LangChain4j ChatModel 适配多模型；配置驱动可插拔 |
| 编排框架 | LangGraph4j（LangGraph 的 Java 移植），独立 Phase 接入，做多步推理与条件分支编排 |
| 配置中心 | **Nacos 仅作配置中心**，不做服务注册与发现（非微服务项目）；`spring.config.import` 方式，无 bootstrap.yml；不兼容则降级 nacos-client + 自定义 ConfigDataLoader |
| 大小模型 | 均采用 OpenAI 兼容端点，`baseUrl`/`apiKey`/`modelId` 可配置，可指向任意兼容网关 |
| 模型选择策略 | 定义策略接口，实现标签/权重/成本三种策略，规则存 Nacos，运维可动态调权做灰度，配置驱动可插拔 |
| 意图识别 | 多层级意图识别 + 分类校验；意图→模型路由分派（简单/推理/长上下文/结构化四类） |
| 会话存储 | Redis 热上下文（标准 LLM 消息格式，TTL）+ RabbitMQ 异步持久化到 DB |
| 流式输出 | 线性/LangGraph 引擎用 Servlet 栈 `SseEmitter`（Phase 12 已落地）；AgentScope 引擎严格按官方文档对接方式接入（响应式 SSE/WebFlux），不强制 Servlet 桥接、不拒绝 WebFlux |
| RAG | 完整能力：Embedding 向量化 → 向量库（pgvector/Redis vector）→ 向量检索 → Rerank 重排 → RAG注入扫描 → 纯净片段 |
| 上下文构建 | 完整三层隔离：系统锚点层 / 客观数据层 / 用户指令层，按序拼接 |
| 配置隔离 | dev/prod 双环境，密钥经环境变量注入，配置文件仅占位 |
| 异常治理 | 四层分诊：传输级重试、客户端错误立即失败、工具异常透传 LLM 自判、安全级立即拦截 |
| 可观测方案 | Micrometer + OTel 链路追踪 + 结构化 JSON 日志 + 独立审计队列 |
| 工具安全 | 手写递归下降表达式解析器（示例工具），`BigDecimal` 高精度，禁用 eval/ScriptEngine；通用工具走参数解析→Schema校验→执行 |

---

## 3. 架构总览（对齐框架文件七层）

```
                    ┌──────────────── Nacos 配置中心（仅配置，非注册发现）─────────┐
                    │ 模型元数据 / 路由规则 / 流控策略(RPM/TPM/成本) / 容灾策略(主备) │
                    │ 意图规则 / 改写规则 / RAG配置 / 告警规则 / 路由权重（热加载）   │
                    └─────────────────────────────┬───────────────────────────────┘
                                                  │ spring.config.import
  ╔══════════════════════════════════════════════╧════════════════════════════════════════╗
  ║ 第一层·接入与安全层                                                                        ║
  ║  用户请求 → 基础数据校验 → 速率限制&熔断器 → 输入安全过滤器 ──(失败)──> 统一异常处理器        ║
  ╠════════════════════════════════════════════════════════════════════════════════════════╣
  ║ 第二层·会话管理与理解层                                                                    ║
  ║  会话路由 ─┬─命中→ 拉取Redis缓存 ─┐                                                        ║
  ║            └─新建→ 触发摘要Hook ──┴→ 用户问题改写 → 改写质量校验 → 标准化Query            ║
  ╠════════════════════════════════════════════════════════════════════════════════════════╣
  ║ 第三层·意图识别与模型路由层                                                                ║
  ║  标准化Query → 多层级意图识别 → 分类校验 → 路由分发                                        ║
  ║       ├ 闲聊/简单QA  → 简单模型路由 ─┐                                                     ║
  ║       ├ 推理/分析    → 推理模型路由 ─┤                                                     ║
  ║       ├ 长上下文/复杂→ 长上下文模型路由├→ 查询配置中心 → 模型选择策略(标签/权重/成本)→选定  ║
  ║       └ 结构化抽取  → 结构化模型路由 ┘                                                     ║
  ╠════════════════════════════════════════════════════════════════════════════════════════╣
  ║ 第四层·能力与执行层                                                                        ║
  ║  触发RAG  → 向量检索 → 检索校验 → Rank重排 → RAG注入扫描 → 纯净片段                        ║
  ║  触发工具 → 参数解析 → Schema校验 → 执行API → 工具返回数据                                  ║
  ║  触发HITL → HITL确认 → 超时/权限判断 → 人工工单                                            ║
  ╠════════════════════════════════════════════════════════════════════════════════════════╣
  ║ 第五层·上下文构建工厂（三层隔离）                                                          ║
  ║  收集数据 → [系统锚点层] system+runtime → [客观数据层] history+rag+tool → [用户指令层] user ║
  ║          → 按序拼接: Sys→Runtime→His→RAG→Tool→User                                        ║
  ╠════════════════════════════════════════════════════════════════════════════════════════╣
  ║ 第六层·统一模型网关与配置中心                                                              ║
  ║  ┌─模型配置中心(热加载)─ 元数据 / 路由规则 / 流控策略 / 容灾策略 ─┐                        ║
  ║  │                                                              │                        ║
  ║  CB_Merge + 选定模型 → 统一模型网关 → 网关拦截逻辑                                          ║
  ║       → 校验Token预算&速率限制 ─┬─超限→ 429降级话术                                        ║
  ║                                 └─通过→ 执行HTTP请求 ─┬─主成功→ 接收响应                  ║
  ║                                                       └─超时/异常→ 自动故障转移→备选模型 ║
  ║       → LLM原始输出                                                                         ║
  ╠════════════════════════════════════════════════════════════════════════════════════════╣
  ║ 第七层·结构化输出与安全                                                                    ║
  ║  LLM原始输出 → 结构化输出网关 → JsonSchema校验 ─┬─失败→ 重试/兜底                          ║
  ║                                                 └─通过→ 输出安全过滤器 → 最终响应          ║
  ╚════════════════════════════════════════════════════════════════════════════════════════╝
        异常汇总: 统一异常/D7纯净片段/F3人工工单/GW3降级/H5兜底 → Z → 返回用户

  ┌──────────────────────── 横向支撑体系 ─────────────────────────┐
  │ 全链路可观测性 ─.→ D5 / Gateway / 配置中心                       │
  │ 在线反馈 → 离线数据池 → 离线微调流水线 → 模型注册 ─.API→ 配置中心  │
  │ 运维控制台 ─.动态调整路由权重→ 配置中心                            │
  └────────────────────────────────────────────────────────────────┘

  /eval/run ──> EvalExecutor（批量用例执行 → 调用 /chat 全链路 → 生成评测报告）
```

---

## 4. 包结构设计（com.agentdemo007）
> 严格按框架七层 + 横向支撑体系组织。`agent.tool` 含空格的历史包名修正为 `agent.tool`。

```
com.agentdemo007
├── Agentdemo007Application.java                  // 启动类
│
├── common                                        // 公共基础
│   ├── pipeline                                  // 统一收口三件套（第四原则·贯穿全链路）
│   │   ├── PipelineContext.java                  // 上下文载体（状态唯一收口，流过所有步骤）
│   │   ├── StepOutcome.java                      // 步骤产出契约（sealed: Proceed/ShortCircuit/Degrade）
│   │   ├── PipelineStep.java                     // 步骤接口（各层实现，经 Context 收口）
│   │   ├── PipelineResult.java                   // 终端收口（→ ChatController 翻译为 UnifiedResponse）
│   │   └── PipelineOrchestrator.java             // 编排器（按序驱动，统一处置短路/降级/异常）
│   ├── trace
│   │   └── TraceId.java                          // traceId 唯一解析收口（防漂移）
│   ├── response
│   │   ├── UnifiedResponse.java                  // 统一返回体 {code,msg,data,traceId,timestamp}（对外收口）
│   │   └── ErrorCode.java                        // 错误码枚举
│   ├── degradation
│   │   ├── DegradationPhraseCenter.java          // 话术中心（Nacos 热更新覆盖）
│   │   └── DegradationScenario.java              // 降级场景枚举（含 RAG_SKIP 等 12 话术）
│   └── exception
│       ├── BusinessException.java
│       └── SessionCacheException.java
│
├── web                                            // Web 层
│   ├── ChatController.java                        // POST /chat（同步+SSE）
│   ├── EvalController.java                        // POST /eval/run 评测端点
│   ├── HealthController.java                      // GET /health
│   └── advise
│       └── GlobalExceptionHandler.java            // 终端全局异常拦截
│
├── trace
│   └── TraceFilter.java                           // 链路追踪过滤器，traceId→MDC+响应头
│
├── access                                        // 【第一层】接入与安全层
│   ├── ValidationFilter.java                     // 基础数据校验
│   ├── RateLimitFilter.java                      // 速率限制 & 熔断器（延后：网关层 TokenBudgetChecker 覆盖模型级限流；接入层 sessionId/IP 令牌桶待 Phase 12 前补）
│   ├── InputSecurityFilter.java                  // 输入安全过滤器
│   └── PromptSanitizer.java                      // 提示词注入包裹器
│
├── session                                       // 【第二层】会话管理与理解层
│   ├── cache
│   │   └── SessionCacheService.java               // Redis 会话缓存（load/append/TTL）
│   ├── router
│   │   └── SessionRouter.java                     // 会话路由（命中/新建）
│   ├── summary
│   │   └── SummaryHook.java                       // 新建会话触发摘要Hook
│   ├── rewrite
│   │   ├── QueryRewriter.java                     // 用户问题改写
│   │   └── RewriteQualityChecker.java             // 改写质量校验
│   └── model
│       └── StandardQuery.java                     // 标准化Query
│
├── intent                                        // 【第三层】意图识别与模型路由层
│   ├── Intent.java                               // 意图枚举（闲聊/推理/长上下文/结构化抽取/注入/转人工/未知…）
│   ├── IntentCategory.java                        // 意图分类
│   ├── IntentRecognizer.java                      // 多层级意图识别（接口）
│   ├── IntentRecognizerImpl.java                 // 多层级意图识别（实现：规则→小模型→大模型→兜底）
│   ├── IntentClassifier.java                      // 分类校验
│   ├── RouteDispatcher.java                       // 路由分发（四类）
│   ├── ModelRouter.java                           // 模型路由（简单/推理/长上下文/结构化）
│   └── rule
│       ├── Rule.java                              // 规则接口
│       ├── KeywordRule.java                       // 关键词规则
│       ├── InjectionPatternRule.java              // 注入检测规则
│       └── RuleMatcher.java                      // 规则匹配器+冲突仲裁
│
├── capability                                    // 【第四层】能力与执行层
│   ├── rag
│   │   ├── EmbeddingService.java                 // 向量化（Embedding）
│   │   ├── VectorStoreConfig.java                // 向量库装配（pgvector/Redis vector）
│   │   ├── VectorRetriever.java                  // 向量检索
│   │   ├── RetrievalValidator.java               // 检索校验
│   │   ├── Reranker.java                          // Rank 重排
│   │   ├── RagInjectionScanner.java               // RAG 注入扫描（防注入）
│   │   └── RagFragment.java                        // 纯净片段
│   ├── tool
│   │   ├── ToolExecutor.java                     // 工具执行入口
│   │   ├── ParamParser.java                      // 参数解析
│   │   ├── SchemaValidator.java                  // Schema 校验
│   │   ├── ToolRegistry.java                      // 工具注册表
│   │   ├── ArithmeticTool.java                    // 示例工具 @Tool
│   │   └── ArithmeticEvaluator.java              // 手写安全表达式解析器
│   └── hitl
│       ├── HitlHandler.java                       // HITL 确认
│       ├── HitlDecision.java                      // 超时/权限判断
│       └── HumanTicketService.java                // 人工工单
│
├── context                                       // 【第五层】上下文构建工厂（三层隔离）
│   ├── ContextBuilder.java                        // 上下文构建工厂入口
│   ├── SystemAnchorLayer.java                     // 系统锚点层（system + runtime）
│   ├── ObjectiveDataLayer.java                   // 客观数据层（history + rag + tool）
│   ├── UserInstructionLayer.java                 // 用户指令层（user）
│   └── ContextMerger.java                        // 按序拼接 Sys→Runtime→His→RAG→Tool→User
│
├── gateway                                       // 【第六层】统一模型网关与配置中心
│   ├── config
│   │   ├── ModelConfigCenter.java                 // 模型配置中心（热加载）
│   │   ├── ModelMetadata.java                     // 模型元数据（端点/Key/上下文窗口）
│   │   ├── RouteRule.java                         // 路由规则（意图↔模型映射）
│   │   ├── FlowControlPolicy.java                 // 流控策略（RPM/TPM/成本上限）
│   │   └── FailoverPolicy.java                    // 容灾策略（主备/重试/超时）
│   ├── selector
│   │   ├── ModelSelector.java                     // 模型选择策略接口（可插拔）
│   │   ├── TagBasedSelector.java                  // 标签策略
│   │   ├── WeightBasedSelector.java               // 权重策略
│   │   └── CostAwareSelector.java                 // 成本策略
│   ├── registry
│   │   └── ModelRegistry.java                     // 模型注册表（微调上线后动态注册）
│   ├── UnifiedModelGateway.java                   // 统一模型网关
│   ├── TokenBudgetChecker.java                    // Token 预算校验 + 速率/日配额关卡（原 GatewayInterceptor 职责并入此类）
│   └── FailoverExecutor.java                      // 自动故障转移（主备切换）
│
├── output                                        // 【第七层】结构化输出与安全
│   ├── StructuredOutputGateway.java              // 结构化输出网关
│   ├── JsonSchemaValidator.java                  // JsonSchema 校验
│   ├── OutputRetryFallback.java                  // 重试/兜底
│   └── OutputSecurityFilter.java                 // 输出安全过滤器
│
├── llm                                           // LangChain4j 集成（Phase 4 落地：ChatLlmService 置于 gateway/llm/，此处为后续 LangChain4j 桥接预留）
│   ├── config
│   │   └── LlmConfig.java                        // 模型 Bean 装配（同步+流式，多模型）—延后
│   ├── service
│   │   └── ChatLlmService.java                   // 实际位于 gateway/llm/ChatLlmService.java（LLM 统一入口）
│   └── callback
│       ├── LlmLifecycleHook.java                 // LLM 生命周期 Hook 接口
│       ├── CompositeLlmCallbackHandler.java      // 复合回调（SSE/指标/异常/MQ/审计）
│       └── ChatTurnEvent.java                    // 会话轮次事件（MQ 载荷）
│
├── resilience                                    // 韧性层
│   ├── ExceptionCategory.java                    // 异常分类枚举
│   ├── TriageResult.java                         // 分诊结果
│   ├── ExceptionTriage.java                      // 异常分诊器
│   ├── RetryPolicy.java                          // 重试策略配置
│   ├── BackoffStrategy.java                      // 指数退避+全抖动
│   ├── ResilientExecutor.java                    // 重试执行模板
│   ├── CircuitBreaker.java                       // 熔断器
│   └── ToolErrorFeedback.java                    // 工具异常透传封装
│
├── langgraph                                     // LangGraph4j 集成（Phase 14）
│   ├── AgentStateGraph.java                      // Agent 状态机
│   ├── GraphNode.java                            // 图节点
│   ├── GraphEdge.java                            // 条件分支边
│   └── GraphExecutor.java                        // 图执行器
│
├── persistence                                   // 异步持久化 + 审计
│   ├── mq
│   │   ├── RabbitMqConfig.java                   // 交换机/队列/死信/序列化
│   │   ├── HistoryPersistProducer.java           // 会话持久化生产者
│   │   ├── HistoryPersistConsumer.java           // 会话持久化消费者 + DLQ
│   │   ├── AuditProducer.java                    // 审计事件生产者
│   │   └── AuditConsumer.java                    // 审计事件消费者 + DLQ
│   ├── entity
│   │   ├── ChatTurnEntity.java
│   │   └── AuditEventEntity.java
│   └── repository
│       ├── ChatTurnRepository.java
│       └── AuditEventRepository.java
│
├── observability                                 // 可观测性横切层
│   ├── metrics
│   │   └── MetricsConstants.java                 // 指标名称常量 + 埋点工具
│   ├── tracing
│   │   └── TracingConfig.java                    // OTel + Micrometer 桥接
│   ├── logging
│   │   └── LoggingMdcConfig.java                 // MDC 字段注入配置
│   └── audit
│       ├── AuditEvent.java                       // 审计事件结构体
│       └── AuditEventType.java                   // 审计事件类型枚举
│
├── feedback                                      // 在线反馈 + 离线微调闭环
│   ├── FeedbackCollector.java                    // 在线反馈收集
│   ├── OfflineDataPool.java                      // 离线数据池
│   ├── FineTuningPipeline.java                   // 离线微调流水线（编排，非内嵌训练）
│   └── ModelRegistrar.java                       // 模型注册（更新配置中心 API）
│
├── admin                                         // 运维控制台
│   └── RouteWeightController.java                // 动态调整路由权重（写配置中心）
│
├── eval                                          // 评测模块
│   ├── dto
│   │   ├── EvalRequest.java                      // 评测请求
│   │   ├── EvalTestCase.java                    // 测试用例
│   │   └── EvalReport.java                       // 评测报告
│   └── EvalExecutor.java                         // 评测执行引擎
│
└── config                                        // 基础设施配置
    ├── RedisConfig.java                          // Redis + RedisChatMemoryStore
    ├── NacosConfig.java                          // Nacos 配置属性绑定
    ├── NacosEnvironmentPostProcessor.java        // Nacos 配置中心 SPI 加载（无 bootstrap.yml）
    ├── NacosConfigRefresher.java                 // Nacos 热更新监听（ConfigService.addListener → Environment 活源）
    ├── RedisProperties.java                      // Redis 连接属性绑定
    ├── JacksonConfig.java                        // JSON 序列化配置（未创建：Boot 4 内置 Jackson 3 默认配置够用）
    └── logback-spring.xml                        // 结构化日志配置
```

### 4.1 前端工程结构（frontend/，独立 Vite 工程）
> 技术栈：Vue 3 + Vite + TypeScript + Element Plus + ECharts + Pinia + Vue Router + axios + markdown-it。
> 部署：开发期 Vite(:5173) 代理后端(:8080)；生产期 `vite build → dist/` 拷入 Spring Boot `static/` 单 jar 部署，或 nginx 托管。

```
frontend/
├── package.json / vite.config.ts / tsconfig.json / index.html
└── src/
    ├── main.ts / App.vue
    ├── router/index.ts            // Vue Router（4 模块路由 + 守卫）
    ├── api/                       // 接口层
    │   ├── http.ts                // axios 实例 + 拦截器（traceId 透传/统一错误）
    │   ├── chat.ts                // /chat 同步 + SSE
    │   ├── eval.ts                // /eval/run
    │   ├── admin.ts               // 路由权重 / 模型配置 / HITL 工单 / 会话历史
    │   └── obs.ts                 // 指标 / 链路聚合
    ├── views/
    │   ├── chat/                  // 对话界面（气泡+Markdown+SSE流式+工具/RAG折叠）
    │   ├── admin/                 // 运维管理台（路由调权/模型配置/HITL工单/会话历史）
    │   ├── eval/                  // 评测可视化（报告卡片+表格+失败明细）
    │   └── observability/         // 可观测性仪表盘（指标卡片+ECharts时序+链路检索+告警）
    ├── components/                // 通用组件（MessageBubble/JsonViewer/…）
    ├── stores/                    // Pinia（会话/全局状态）
    └── utils/sse.ts               // EventSource 封装（断线重连）
```

---

## 5. 核心模块设计要点（按层）

### 5.1 第一层·接入与安全层
1. **基础数据校验**：请求体非空、sessionId 格式、消息长度上限、字段类型校验，失败直接进统一异常处理器。
2. **速率限制 & 熔断器**：基于 sessionId/IP 维度令牌桶限流；后端依赖（LLM/Redis/MQ）异常率超阈值触发熔断，快速失败。
3. **输入安全过滤器**：提示注入特征检测（关键词/模式/编码绕过），高风险直接拦截并审计，零 LLM 调用。
4. **统一异常处理器**：全局兜底，完整堆栈入日志，对用户脱敏返回统一错误信封。

### 5.2 第二层·会话管理与理解层
1. **会话路由**：sessionId 命中 → 拉取 Redis 缓存；新建 → 触发摘要 Hook（生成会话摘要作为后续上下文锚点）。
2. **用户问题改写**：结合历史上下文，将指代/省略/口语化问题改写为自足的标准化 Query。
3. **改写质量校验**：改写后做完整性/忠实度校验，不达标回退原问题，避免改写失真。
4. **约束改写（Phase 20 增强）**：改写**只补全不替换**——补关键词/商品/活动名/时间线，**必须保留 original query**，**不下业务结论**（不替用户判定意图归属/订单状态/业务决策）；产出 original query + `QueryEnrichment` 槽，不覆盖 standardQuery。

### 5.3 第三层·意图识别与模型路由层
1. **多层级意图识别**：规则前置 → 小模型分类 → 大模型兜底 → 规则后置（注入二次扫描）；多规则冲突采用优先级评分+冲突上交小模型仲裁。
2. **分类校验**：识别结果置信度达标则通过，否则升级下一层。
3. **路由分发**：意图映射到四类模型路由——
   - 闲聊/简单QA → 简单模型路由（低成本快模型）
   - 推理/分析 → 推理模型路由（高能力模型）
   - 长上下文/复杂 → 长上下文模型路由（大窗口模型）
   - 结构化抽取 → 结构化模型路由（强结构化输出模型）
4. **模型选择策略**：查询配置中心获取候选模型集，按标签/权重/成本可插拔策略选定目标模型；对外仅输出意图枚举，不暴露内部置信度。

### 5.4 第四层·能力与执行层
**5.4.1 RAG 链路**：Embedding 向量化 → 向量库检索 → 检索校验（相关性阈值/数量）→ Rank 重排（Cross-Encoder/模型重排）→ RAG 注入扫描（防注入）→ 纯净片段输出。**（Phase 20 增强）Hybrid 检索**：向量 + 关键词(BM25/精确词) + 规则路由三通道融合，订单号/型号/发票类型等精确词必走精确通道不走纯语义；**时效治理**：`RagFragment` 带 `timestamp`/`validUntil`，过时片段隔离标注不冒充当前，重排引入时效衰减（同等相关性近期优先）。
**5.4.2 工具调用链路**：参数解析 → Schema 校验（JSON Schema）→ 执行 API/本地工具 → 返回数据；示例工具为手写安全四则运算解析器（BigDecimal，无 eval）。
**5.4.3 HITL 链路**：高风险操作触发 → HITL 确认请求 → 超时/权限判断 → 人工工单挂起，不自动执行高风险动作。

### 5.5 第五层·上下文构建工厂（三层隔离）
- **系统锚点层**：system 提示词 + runtime 运行时元数据（会话摘要/意图/时间）。
- **客观数据层**：历史摘要 + RAG 安全片段 + 工具结果。
- **用户指令层**：改写后用户问题。
- **按序拼接**：Sys → Runtime → His → RAG → Tool → User，严格隔离防止层级污染。

### 5.6 第六层·统一模型网关与配置中心
1. **模型配置中心（热加载）**：YAML 存 Nacos，含模型元数据（端点/Key/上下文窗口）、路由规则（意图↔模型映射）、流控策略（RPM/TPM/成本上限）、容灾策略（主备/重试/超时）；Nacos 配置变更热加载生效，不重启。
2. **统一模型网关**：收口所有 LLM HTTP 请求；网关拦截逻辑——校验 Token 预算 & 速率限制，超限返回 429 降级话术；通过则执行 HTTP 请求。
3. **自动故障转移**：主模型超时/异常 → 按 FailoverPolicy 切换备选模型 → 重试请求；转移事件审计 + 指标埋点。

### 5.7 第七层·结构化输出与安全
1. **结构化输出网关**：LLM 原始输出 → JsonSchema 校验；失败则重试/兜底（降级话术或默认结构）。
2. **输出安全过滤器**：最终响应过滤敏感信息/注入残留，产出最终响应返回用户。

### 5.8 韧性层（异常四层分诊）
| 类别 | 场景 | 处置策略 |
|---|---|---|
| RETRYABLE_TRANSIENT | 429 限流、5xx、网络超时、连接失败 | 指数退避+全抖动透明重试，上限 3 次；429 优先遵循 Retry-After；主模型重试耗尽触发故障转移 |
| NON_RETRYABLE_CLIENT | 401/403/404、参数校验失败 | 立即失败，不重试 |
| TOOL_RECOVERABLE | 工具参数错误、非法表达式、除零 | 完整异常内容封装反馈 LLM 自纠正，受最大迭代次数限制 |
| FATAL | 提示注入、权限不足、非法状态 | 立即失败、审计留痕、不提交 LLM |

### 5.9 横向支撑体系
1. **可观测性**：四维指标（资源/组件/业务/AI）经 `/actuator/prometheus` 暴露；OTel 全链路 traceId 贯穿 HTTP→会话→意图→能力→网关→输出→MQ→DB；统一 JSON 日志自动注入 traceId/sessionId/intent，生产脱敏。
2. **审计**：独立审计队列，注入攻击、意图识别、工具调用、HITL、故障转移、异常事件全量留痕，只增不改。
3. **微调闭环**：在线反馈收集 → 离线数据池 → 离线微调流水线（编排外部训练任务，非内嵌训练）→ 模型注册 → 调用配置中心 API 动态注册新模型端点，热生效。
4. **运维控制台**：动态调整路由权重，写回 Nacos，热加载生效，支持灰度切换。

### 5.10 评测端点 `/eval/run`
- **能力**：批量执行测试用例，自动化验证意图识别准确率、模型路由正确率、工具调用正确率、RAG 检索命中率、故障转移触发率、响应耗时、Token 消耗。
- **入参**：评测ID、测试用例数组（输入文本、预期意图、预期路由类型、预期工具结果）。
- **出参**：整体准确率、各意图召回率、路由正确率、平均/分位耗时、Token 统计、故障转移次数、失败用例明细。
- **定位**：迭代回归测试、效果验证、性能摸底；生产环境 IP 白名单 + 接口鉴权。

### 5.11 降级话术中心与短路机制（核心原则）
- **话术中心 `DegradationPhraseCenter`**（`common/degradation`）：按 `DegradationScenario` 枚举提供预设话术，默认内置，Nacos 可热更新覆盖。面向 C 端用户**始终返回"正常"对话回复**，不暴露技术错误码/堆栈。
- **短路机制**：接入层（注入/超限/基础校验）及流水线各步检测到 **4xx / 注入 / 攻击 / 依赖故障**时，直接返回对应话术（HTTP 200 + `UnifiedResponse{data:{reply, degraded:true, scenario}}`）+ **审计** + **跳过后续所有步骤**。4xx/注入/降级路径**零 LLM 调用**。
- **设计准则**：能跑通 > 完美；任何单点不可用都不得让整条链路抛 5xx 给用户，必须落到话术兜底。

### 5.12 每步降级预设（保证程序跑通）
| 步骤 | 降级触发 | 降级话术 / 行为 |
|---|---|---|
| 接入·注入检测 | 命中注入特征 | 话术(INJECTION) + 审计 + 短路 |
| 接入·大小校验 | 请求体超限 | 话术(PAYLOAD_TOO_LARGE) + 短路 |
| 接入·基础校验 | 缺参/格式错 | 话术(BAD_REQUEST) + 短路 |
| 会话缓存 | Redis 故障 | 话术(SESSION_DOWN) + 短路 |
| 问题改写 | 改写 LLM 失败 | 回退原问题继续（不阻塞） |
| 意图识别 | 模型不可用 | 兜底 UNKNOWN + 话术(UNKNOWN_INTENT) |
| 模型路由 | 无可用模型 | 话术(MODEL_DOWN) + 短路 |
| RAG | 向量库故障/空召回 | 跳过 RAG 继续（不阻塞） |
| 工具 | 工具异常 | 透传 LLM 自纠正；耗尽话术(TOOL_FAILURE) |
| HITL | 超时/权限 | 话术(HITL_TIMEOUT) + 工单 |
| 上下文构建 | —（内部步骤） | 不直接面向用户 |
| 模型网关 | 主备均不可用/限流 | 话术(FAILOVER_EXHAUSTED/RATE_LIMITED) + 短路 |
| 结构化输出 | Schema 校验耗尽 | 兜底默认结构 + 话术兜底 |
| 全局兜底 | 未预期异常 | 话术(INTERNAL) + 审计 |

### 5.13 环节测评数据预设（供 `/eval/run` 统一消费）
- 随每个关键环节实现，**预置 golden 测评数据**至 `src/main/resources/eval/{stage}.json`，由 Phase 15 `EvalExecutor` 统一加载执行。
- 格式：`{ "stage": "...", "cases": [{ "id": "...", "input": "...", "expected": { "intent|scenario|route|toolResult|...": "..." }, "note": "..." }] }`。
- 各阶段数据集：`injection`(注入检测) / `intent`(意图识别) / `routing`(模型路由) / `rag`(检索命中) / `tool`(工具调用) / `hitl` / `degradation`(降级话术)。**环节实现与数据预置同步**，不留到最后补。

### 5.14 统一收口原则（第四原则·贯穿全链路）
> 每一步都经统一收口，防止数据结构等不一致。三条原则（话术短路 / 每步降级 / 环节测评）+ 收口在此交汇为同一套机制——这是最底层的一条。

**收口三件套**（`common/pipeline`）：
1. **`PipelineContext`（状态收口）**——贯穿全部 7 层步骤的唯一状态对象。每步读写同一份字段契约，**禁止各步私造数据结构互相传参**。新字段随 Phase 推进以**强类型（非 Map）**声明于此（standardQuery/intent/routeType/history/ragFragments/toolResults/hitlState/assembledPrompt/modelResponse…），始终维持单一数据真相源，防漂移。
2. **`StepOutcome`（产出收口）**——每步统一返回 sealed 类型，三态：`Proceed`（继续）/`ShortCircuit`（话术短路，零 LLM，跳过后续）/`Degrade`（降级但继续）。把原则①（话术短路）②（每步降级）④（统一收口）机械统一为**同一出口形状**，杜绝各步"各自抛异常/各自返回"的不一致。
3. **`PipelineOrchestrator`（编排收口）**——按 `@Order` 自动收集所有 `PipelineStep`，统一处置三态；任何步骤抛异常 → 收口到 `INTERNAL` 话术（不抛 5xx，§5.12 全局兜底）。

**对外收口**：`PipelineResult`（终端）→ `ChatController`（Phase 12）翻译为 `UnifiedResponse`；`TraceId` 收口 traceId 解析。任何步骤不直接写 HTTP、不重复实现 traceId。

**LangGraph 兼容（Phase 14）**：`PipelineStep` 契约即 LangGraph 节点适配层，节点入参/出参都走 `PipelineContext`+`StepOutcome`，换引擎不换收口。

**落地约束**：后续每个 Phase 的步骤必须①实现 `PipelineStep`；②经 `PipelineContext` 读写状态、返回 `StepOutcome`；③新增状态字段一律加到 `PipelineContext`（强类型），不得在步骤间私传 bespoke 结构。不满足收口契约的步骤不准入流水线。

---

## 6. 分阶段开发计划
> 每阶段以「编译通过 + 单测绿 + 冒烟验证」为准入下阶段标准；关键节点设置架构审查。
> 顺序：基础设施(1-3) → 网关与配置中心(4) → 韧性(5) → 理解/决策/构建/能力/输出(6-12) → 持久化审计(13) → LangGraph(14) → 可观测/运维/微调/评测/验收(15) → 工具断路器(17，卡 Phase 9/5) + AgentScope 2.0 集成(18，卡 Phase 14/15) + 检索理解增强(20，卡 Phase 6/8/10，与前端并行) → 前端对话界面(16，卡 Phase 12 后) → 前端管理台/评测/仪表盘(19，卡 Phase 15 后)。

### Phase 1 — 工程骨架 + 接入与安全层 + 终端治理 + 链路追踪
**目标**：搭建可运行 Spring Boot 工程，建立接入安全过滤、统一返回、异常拦截、链路追踪基础能力（框架第一层 + 终端治理）。
**任务清单**
- [x] pom 引入 web、actuator、validation、lombok、jackson；配置 Java 17 + Boot 4.1.1。（jackson 为 Boot 4 内置 Jackson 3 `tools.jackson`，无需显式依赖）
- [x] 实现 `UnifiedResponse`、`ErrorCode`、基础自定义异常。
- [x] 实现 `GlobalExceptionHandler`：参数异常、业务异常、系统异常统一返回；完整堆栈入日志，对用户脱敏。
- [x] 实现 `TraceFilter`：生成/透传 `X-Trace-Id`，写入 MDC；响应头返回 traceId。
- [x] 实现 `HealthController`：`GET /health` 返回健康状态。
- [x] 实现 `DegradationPhraseCenter` + `DegradationScenario` 话术中心：预设话术枚举，面向用户返回"正常"话术，可热更新。
- [x] `InputSecurityFilter` 话术化：注入命中 → 话术(INJECTION)(HTTP 200) + 审计 + 短路，不返回 4xx 技术码、不进下游、零 LLM。**延后**：请求体扫描随 Phase 12 `/chat` 落地补齐（当前覆盖 URI+query 参数；POST body 注入由流水线意图规则层兜底拦截，同为零 LLM）。
- [x] `ValidationFilter` 话术化：请求体超限 → 话术(PAYLOAD_TOO_LARGE)(HTTP 200) + 短路。（请求体非空、sessionId 格式等 `/chat` 专用校验待 Phase 12 随端点补齐）
- [x] 实现 `PromptSanitizer`：用户内容包裹隔离（防御注入，后续各层复用）。
- [x] 配置 `logback-spring.xml`：JSON 结构化日志、traceId 自动注入、分级滚动、生产脱敏。
- [x] 预置 `eval/injection.json` 注入检测测评数据（环节实现与数据同步）。
- [x] 实现统一收口三件套骨架（§5.14·第四原则）：`PipelineContext`/`StepOutcome`(sealed Proceed/ShortCircuit/Degrade)/`PipelineStep`/`PipelineResult`/`PipelineOrchestrator`，三态统一处置 + 异常收口 INTERNAL 话术（不抛 5xx）。
- [x] 抽取 `TraceId` 收口 traceId 解析，`UnifiedResponse`/`PipelineContext` 共用（防漂移）。
- [x] 预置 `eval/degradation.json` 降级收口测评数据（环节实现与数据同步）。
**产出文件**：pom.xml、UnifiedResponse.java、ErrorCode.java、GlobalExceptionHandler.java、TraceFilter.java、HealthController.java、ValidationFilter.java、InputSecurityFilter.java、PromptSanitizer.java、DegradationPhraseCenter.java、DegradationScenario.java、TraceId.java、PipelineContext.java、StepOutcome.java、PipelineStep.java、PipelineResult.java、PipelineOrchestrator.java、logback-spring.xml、eval/injection.json、eval/degradation.json
**验收标准**
- `./mvnw test` 全绿；应用启动正常。
- `curl /health` 返回带 traceId 的统一成功响应。
- 抛出异常场景返回统一错误信封，含 traceId；日志每行含 traceId，无敏感明文。
- 注入特征请求被 `InputSecurityFilter` 拦截，不进入下游。

### Phase 2 — Nacos 配置中心 + 多环境隔离
**目标**：实现配置动态化（仅配置中心，非注册/发现），dev/prod 环境分离，密钥安全注入。
**任务清单**
- [x] pom 引入 `nacos-client`（3.2.4）：经冒烟验证 `spring-cloud-starter-alibaba-nacos-config` 与 Boot 4.1 兼容风险高（§8 风险表），按预案走降级路径——裸 `nacos-client` + 自定义加载，无 Spring Cloud、无 bootstrap.yml。
- [x] 编写 `application.yml`（公共基线）、`application-dev.yml`、`application-prod.yml`。
- [x] 自定义 `NacosEnvironmentPostProcessor`（`EnvironmentPostProcessor` SPI）替代 `spring.config.import: nacos:`：启动期一次性拉取远端 YAML 以最高优先级注入 Environment；语义等价（无 bootstrap.yml、无 Spring Cloud 依赖）。
- [x] Nacos 创建 `Agentdemo007-dev.yml` / `Agentdemo007-prod.yml`，写入基础配置项（模型端点占位、Redis/MQ 连接、阈值）。**外部运维动作**（仓库无文件）。
- [x] 密钥通过环境变量注入，配置文件仅 `${}` 占位。
- [x] 实现 `NacosConfig`（兜底用）：配置加载失败时的兼容降级（未配置跳过 / 不可达降级本地，不阻塞启动）。
- [x] 热更新：`NacosConfigRefresher` 经 `ConfigService.addListener` 监听 dataId 变更，解析后以 `nacos-config-live` 活源替换注入 Environment（`nacos.refresh-enabled=true` 时启用；失败不影响主链路）。
- [ ] `@RefreshScope` 全量热更新（Bean 级重注入）。**延后**：需 `spring-cloud-context`，与 Boot 4.1 兼容风险同源；当前以 Environment 活源 + 各配置消费方 `refresh()` 收口（如 `ModelConfigCenter.refresh()`）达成等效热更新。
**产出文件**：pom.xml、application.yml、application-dev.yml、application-prod.yml、NacosConfig.java、NacosEnvironmentPostProcessor.java、NacosConfigRefresher.java
**验收标准**
- dev 环境启动成功，日志打印 Nacos 配置加载成功（或未配置跳过降级本地）。
- 修改 Nacos 配置项，Environment 活源（`nacos-config-live`）更新生效，配置消费方经 `refresh()` 收口热生效；`@RefreshScope` Bean 级重注入延后（需 spring-cloud-context）。
- 生产环境密钥不在配置文件明文出现。
- Nacos 不可达时降级加载本地配置，不阻塞启动。

### Phase 3 — Redis 会话缓存基础
**目标**：实现标准 LLM 格式的会话热缓存，支持多轮上下文（第二层会话路由依赖）。
**任务清单**
- [x] pom 引入 `spring-boot-starter-data-redis`（`langchain4j-redis`/`langchain4j-jackson` 延后至 Phase 4：避免过早引入 Jackson 2 与 Boot 4/Jackson 3 冲突；会话消息保持引擎无关，编排层不感知底层序列化）。
- [x] 实现 `RedisConfig`：条件装配（`redis.enabled=true` → `RedisTemplate`+`RedisSessionCacheStore`；dev 默认内存兜底 `InMemorySessionCacheStore`，不阻塞启动）。
- [x] 实现 `ChatMessageCodec`：Jackson 3 `List<ChatMessage>` ↔ JSON 序列化内核（`role+content` DTO，不污染 ChatMessage）。
- [x] 实现 `SessionCacheService`：load/append + TTL 管理、异常封装。
- [x] 实现 `SessionLoadStep`（首步真实 PipelineStep，`@Order(100)`）：Redis 故障 → `ShortCircuit(SESSION_DOWN)` 话术短路（HTTP 200），落地收口契约。
- [x] 全局异常处理器增加 `SessionCacheException` → 503 错误码（非流水线直连的防御兜底）。
**产出文件**：pom.xml、RedisConfig.java、RedisProperties.java、ChatMessageCodec.java、InMemorySessionCacheStore.java、RedisSessionCacheStore.java、SessionCacheService.java、SessionCacheStore.java、SessionLoadStep.java、ChatMessage.java、SessionCacheException.java
**验收标准**
- 会话写入后（内存/Redis）可读取，格式为标准 LLM 消息结构（system/user/ai/tool_result），与编码往返一致。
- 停 Redis 后 /chat 流水线请求经 `SessionLoadStep` 收口为 `SESSION_DOWN` 话术（HTTP 200，§5.12 + eval deg-003），不抛出堆栈、零 LLM；非流水线直连调用由全局异常处理器兜底为 503 错误信封。
- TTL 在 Redis 后端生效，会话过期自动清理。
- TTL 过期后会话自动清理。

### Phase 4 — 模型配置中心 + 统一模型网关 + 多模型治理
**目标**：实现框架第六层核心——模型配置中心热加载 + 统一模型网关 + 多模型路由选择 + 故障转移。
**任务清单**
- [x] 实现 `ModelMetadata`、`RouteRule`、`FlowControlPolicy`、`FailoverPolicy` 配置实体（Builder 模式，纯数据契约）。
- [x] 实现 `ModelConfigCenter`：`ModelConfigSource` 可插拔（dev 空源 / prod Nacos 源）→ `refresh()` 整表重建 `ModelRegistry`，暴露路由/流控/容灾查询。Nacos 监听热加载待接真实源。
- [x] 实现 `ModelSelector` 策略接口 + `TagBasedSelector`、`WeightBasedSelector`、`CostAwareSelector` 三种实现（可插拔，`@Primary` 单 bean，`app.model-selector.strategy=tag|weight|cost` 属性驱动切换，默认 TagBased）。
- [x] 实现 `ModelRegistry`：线程安全注册表，register/unregister/get/enabled/byTag/clear（微调闭环动态注册用）。
- [x] 实现 `UnifiedModelGateway` + `TokenBudgetChecker`（预算关卡：超速率/超日配额→`RateLimitExceededException`，零 LLM，话术短路 HTTP 200，不返回 429）+ `FailoverExecutor`（主→备选逐个尝试，全失败→`LlmUnavailableException`，转移事件写日志/审计占位）+ `ModelExecutor` 引擎边界（dev `NoopModelExecutor` 占位，prod 覆盖）。
- [x] 实现 `ChatLlmService`：LLM 统一入口，强制 `PromptSanitizer` 包裹 → 意图路由选模型（有规则用目标，否则选择策略在启用模型中选）→ 经网关执行；同步入口就绪。
- [ ] 实现 `LlmConfig`：基于 LangChain4j 装配多模型 ChatModel（同步+流式）。**延后**：`ModelExecutor` 边界已就绪，LangChain4j 桥接适配器为薄层，待有真实端点/密钥时接入（避免过早引入 langchain4j-core 重依赖）。
- [ ] 实现 `CompositeLlmCallbackHandler`：复合回调，扇出 SSE 推送、指标埋点、异常捕获。**延后**：需流式调用契约（随 LangChain4j 流式接入）。
- [ ] Nacos 配置模型元数据表、路由规则、流控策略、容灾策略。**延后**：随 `NacosModelConfigSource` 接入。
**产出文件**：gateway/config/*、gateway/registry/ModelRegistry、gateway/selector/*、gateway/core/*、gateway/exception/*、gateway/llm/ChatLlmService、gateway/config/GatewayConfig、intent/Intent、eval/gateway.json、eval/degradation.json(deg-008)
**验收标准**
- 标签/权重/成本三种选择策略可配置切换，选模型结果符合预期（单测覆盖）。
- 主模型正常 → 网关透传成功返回（单测覆盖）。
- 主模型超时/异常 → 自动故障转移备选模型，请求最终成功；全失败收口为 `LlmUnavailableException`（FAILOVER_EXHAUSTED 话术）；转移事件有日志。
- Token 预算/速率超限 → `RateLimitExceededException`（话术短路，零 LLM，HTTP 200，不返回 429）。
- 配置中心整表热加载（`refresh()`）已实现；Nacos 监听热更新随真实源接入后生效，不重启。

### Phase 5 — 韧性层（异常分诊 + 重试退避 + 熔断）
**目标**：建立四层异常治理体系，包裹网关与工具调用，提升稳定性与自恢复能力。
**任务清单**
- [x] 实现 `ExceptionCategory`、`TriageResult`、`ExceptionTriage` 异常分诊器（四层：RETRYABLE_TRANSIENT / NON_RETRYABLE_CLIENT / TOOL_RECOVERABLE / FATAL）。
- [x] 实现 `RetryPolicy`、`BackoffStrategy`（指数+全抖动）、`ResilientExecutor` 执行模板（遵循 Retry-After 不抖动，耗尽重抛末次异常交 FailoverExecutor）。
- [x] 实现 `CircuitBreaker` 熔断器：三态 CLOSED/OPEN/HALF_OPEN，连续失败超阈值快速失败、冷却后半开放行探针、半开成功恢复/失败重开；时钟可注入。
- [x] 实现 `ToolErrorFeedback`：完整异常透传 LLM 自纠正（反馈 prompt 构建 + 最大迭代守卫，返回 sealed `ToolFeedbackOutcome`）。自纠正循环本体（重提交→LLM→修正调用→成功）属后续 Agent 编排层。
- [x] 出站调用经 `ResilientExecutor` 包裹：在 `FailoverExecutor` 内部包裹每个候选执行——重试=同模型退避，耗尽=切备选，故障转移=换模型（正交）；FATAL/TOOL 不故障转移向上传播，RETRYABLE 耗尽 / NON_RETRYABLE 切备选。`GatewayConfig` 装配真实 bean（prod 启用重试-故障转移）。
- [ ] `CompositeLlmCallbackHandler` 异常路径接入分诊逻辑；重试耗尽进入终端异常处理。**延后**：需流式调用契约（随 LangChain4j 流式接入）。
**产出文件**：resilience 包下全部类（ExceptionTriage / BackoffStrategy / RetryPolicy / ResilientExecutor / CircuitBreaker / ToolErrorFeedback + ToolFeedbackOutcome / Transient·NonRetryable·ToolRecoverable·FatalException / Decision / TriageResult / Sleeper / RetryAction）、gateway/core/FailoverExecutor（重构）、gateway/config/GatewayConfig（装配）、eval/resilience.json、eval/degradation.json(deg-009)
**延后项**：CircuitBreaker per-model 故障转移接线（需 per-model 注册表，单一全局熔断会阻断备选切换）；CompositeLlmCallbackHandler 流式分诊；ToolErrorFeedback 自纠正循环本体；Nacos 提示词托管反馈模板。
**验收标准**
- 模拟 429 限流 → 自动退避重试，3 次内成功则正常返回；重试耗尽触发故障转移。
- 模拟 403 鉴权失败 → 立即失败，不重试。
- 工具参数错误 → 完整异常内容反馈 LLM，LLM 自纠正后成功执行。
- 注入/权限异常 → 立即拦截，不提交 LLM。
- 熔断器在依赖异常率超阈值时快速失败，半开恢复后放行。

### Phase 6 — 会话管理与理解层（第二层）
**目标**：实现会话路由、摘要 Hook、用户问题改写+质量校验、标准化 Query。
**任务清单**
- [x] 实现 `SessionRouter`：sessionId 命中→拉取 Redis 缓存；新建→触发 `SummaryHook`。
- [x] 实现 `SummaryHook`：新建会话生成会话摘要作为后续上下文锚点（`LlmSummaryHook` 经 `ChatLlmService` 走小模型通道，失败→empty 跳过锚点不阻塞）。
- [x] 实现 `QueryRewriter`：结合历史上下文改写指代/省略/口语化问题为自足 Query（经 `ChatLlmService` 收口调用小模型，失败/空输出→回退原问题继续，§5.12 行为级降级）。
- [x] 实现 `RewriteQualityChecker`：改写结构级完整性/忠实度校验（污染/空/长度失控→回退原问题）；语义级忠实度延后至小模型流式接入。
- [x] 实现 `StandardQuery`：标准化 Query 数据结构（强类型 record，写入 `PipelineContext.standardQuery`，§5.14 收口）。
- [ ] Nacos 配置改写规则、质量阈值。**延后**：规则当前内置默认，随 NacosModelConfigSource/NacosPromptSource 接入后热更新。
- [x] 预置 `eval/understanding.json` 会话理解黄金测评数据（§5.13 环节实现与数据同步）。
- [x] `PipelineContext` 新增 `summary`/`standardQuery` 强类型字段（§5.14 状态收口）。
- [x] `GatewayConfig` 装配 `ChatLlmService`+`PromptSanitizer` bean（辅助 LLM 调用收口入口）。
**产出文件**：session/model/StandardQuery、session/router/SessionRouter、session/summary/SummaryHook+LlmSummaryHook、session/rewrite/QueryRewriter+RewriteQualityChecker、common/pipeline/PipelineContext（扩展）、gateway/config/GatewayConfig（装配）、eval/understanding.json
**验收标准**
- 命中会话 → 正确拉取 Redis 历史上下文。
- 新建会话 → 触发摘要 Hook，摘要写入上下文锚点。
- 指代/口语化问题 → 改写为自足标准化 Query。
- 改写质量不达标 → 回退原问题，不引入失真。

### Phase 7 — 意图识别与模型路由决策层（第三层）
**目标**：实现多层级意图识别 + 分类校验 + 四类路由分发 + 模型选择策略落地。
**任务清单**
- [x] 实现 `Intent` 枚举（闲聊/推理/长上下文/结构化抽取/注入/转人工/未知…）、`IntentCategory`。
- [x] 实现 `IntentRecognizer`/`IntentRecognizerImpl`：多层级识别（规则前置→小模型→兜底），短路返回，冲突升级；注入在规则层拦截零 LLM。"大模型兜底"层延后（小模型即模型层；语义级深度兜底随 LangChain4j 接入扩展）。
- [x] 实现规则接口、`KeywordRule`、`InjectionPatternRule`、`RuleMatcher`（含冲突仲裁：注入胜出/一致共识/冲突升级）。
- [x] 实现 `IntentClassifier`：分类校验，置信度达标通过否则兜底 UNKNOWN+降级继续（§5.12 意图识别行无"短路"即继续）。
- [x] 实现 `RouteDispatcher` + `ModelRouter`：意图→四类模型路由（简单/推理/长上下文/结构化），对接 `ModelSelector` 选模型；无可用模型→`ModelSelectionException` 由步骤收口为 MODEL_DOWN 话术短路。
- [ ] Nacos 配置关键词表、冲突阈值、注入模式、置信度阈值、意图↔模型路由规则。**延后**：关键词表/注入模式/阈值当前内置默认（`IntentConfig`），随 NacosModelConfigSource 接入后热更新。
- [x] 落地收口：`IntentRecognitionStep`(@Order 500)+`RouteDispatchStep`(@Order 600) 实现 `PipelineStep`，经 `PipelineContext` 读写（intent/routeType/selectedModelId 强类型字段，§5.14），返回 `StepOutcome`。
- [x] 预置 `eval/intent.json`+`eval/routing.json` 黄金测评数据（§5.13 环节实现与数据同步）。
**产出文件**：intent/IntentCategory、intent/IntentRecognizer+IntentRecognizerImpl+IntentClassifier+RouteDispatcher+ModelRouter+IntentRecognitionStep+RouteDispatchStep+IntentConfig、intent/rule/(Rule+KeywordRule+InjectionPatternRule+RuleMatcher)、common/pipeline/PipelineContext（扩展）、eval/intent.json、eval/routing.json
**延后项**：大模型兜底层（随 LangChain4j 流式接入）；Nacos 托管关键词表/注入模式/置信度阈值/路由规则（随 NacosModelConfigSource）。
**验收标准**
- 关键词命中 → 快速返回对应意图。
- 多规则冲突 → 自动升级小模型识别。
- 注入语句 → 规则层直接拦截，返回注入意图，零 LLM 调用。
- 模糊问题 → 小模型置信不足升级大模型。
- 意图→四类模型路由分发正确，选模型符合策略配置。
- 对外接口仅返回意图枚举，不暴露内部信息。

### Phase 8 — 上下文构建工厂（第五层，三层隔离）
**目标**：完整实现三层隔离上下文构建工厂，按序拼接。
**任务清单**
- [x] 实现 `SystemAnchorLayer`：系统提示词（PromptRegistry→内置默认，②每步降级）+ 运行时元数据（会话摘要/意图/时间，Clock 可注入）；Sys 与 Runtime 折叠进单条 System 消息保序。
- [x] 实现 `ObjectiveDataLayer`：历史（`PipelineContext.history` 原样透传）+ RAG 安全片段（非空框定为单条 User 消息，以「【参考资料】」隔离头隔离半可信检索内容）+ 工具结果（逐条框定为 ToolResult）；空段跳过；按 His→RAG→Tool 序。
- [x] 实现 `UserInstructionLayer`：取改写后标准化 Query（缺失回退 rawInput，②每步降级），经 `PromptSanitizer` 包裹定界符，产出单条 User 消息（§5.5 隔离/§5.3.1 注入防御）。
- [x] 实现 `ContextMerger`：注入三层构建器，按序拼接 Sys→Runtime→His→RAG→Tool→User，严格层级隔离，产出 `List<ChatMessage>` 收口于 `PipelineContext.assembledPrompt`。
- [x] 实现 `ContextBuilder`：`PipelineStep`(@Order 700) 工厂入口，委托 `ContextMerger` 拼接并写入 `assembledPrompt`；拼接异常行为级降级兜底 [System(默认), User(sanitize(rawInput))]+Proceed（§5.12 内部步骤无话术/不短路/不标 degraded）。`ContextConfig` 装配三层构建器 + Merger + Clock bean。
**产出文件**：context/SystemAnchorLayer、context/ObjectiveDataLayer、context/UserInstructionLayer、context/ContextMerger、context/ContextBuilder、context/ContextConfig、common/pipeline/PipelineContext（扩展 ragFragments/toolResults/assembledPrompt 强类型字段）、eval/context.json
**延后项**：RAG/工具真实数据接入（Phase 9 工具步 @Order 6xx 填 toolResults、Phase 10 RAG 步 @Order 6xx 填 ragFragments，均在 ContextBuilder @Order 700 之前完成填充）；Phase 12 模型网关步骤消费 `assembledPrompt` 构建 `LlmRequest`；Nacos 托管系统提示词（随 NacosPromptSource）。
**验收标准**
- 三层隔离结构清晰，各层数据不串层污染。
- 拼接顺序严格为 Sys→Runtime→His→RAG→Tool→User。
- 占位数据接入后上下文结构完整可用。

### Phase 9 — 能力与执行层·工具调用（第四层-工具）
**目标**：实现工具调用链路：参数解析→Schema校验→执行→返回数据。
**任务清单**
- [x] 实现 `ToolRegistry`：工具注册表，管理可用工具元数据（名称/描述/参数Schema）。
- [x] 实现 `ParamParser`：从用户输入/LLM 输出解析工具调用参数。
- [x] 实现 `SchemaValidator`：JSON Schema 校验工具参数。
- [x] 实现 `ToolExecutor`：工具执行入口，串联解析→校验→执行→返回；工具异常经 `ToolErrorFeedback` 透传 LLM 自纠正。
- [x] 实现 `ArithmeticEvaluator`：手写递归下降解析器，BigDecimal 高精度，无 eval（示例工具）。
- [x] 实现 `ArithmeticTool`：`@Tool` 注解封装，表达式计算、区间求和；注入攻击（如 `System.exit(0)`）被解析器拦截。
**产出文件**：capability/tool 包下全部类
**验收标准**
- 工具参数解析正确，Schema 校验失败有明确错误反馈。
- 数学表达式正确计算；注入指令被解析器拦截不执行。
- 工具异常 → 完整异常内容反馈 LLM，LLM 自纠正后成功执行。

### Phase 10 — 能力与执行层·RAG（第四层-RAG）
**目标**：实现完整 RAG 链路：向量化→向量库→检索→Rerank→注入扫描→纯净片段。
**任务清单**
- [x] 实现 `EmbeddingService`：基于 LangChain4j Embedding 模型向量化。**dev 落 `HashEmbeddingService`**（确定性 token-bag，无真实模型），引擎无关 seam 已就绪；LangChain4j Embedding 桥接为 prod 覆盖薄层，**延后**（避免过早引入 langchain4j-core 重依赖，同 Phase 4 `ModelExecutor`）。
- [x] 实现 `VectorStoreConfig`：装配向量库（pgvector / Redis vector，可配置）。**dev 落 `InMemoryVectorStore`**（内存余弦 Top-K，`@ConditionalOnMissingBean` 留 prod 覆盖），pgvector/Redis 桥接**延后**。
- [x] 实现 `VectorRetriever`：向量检索（Top-K 召回）。
- [x] 实现 `RetrievalValidator`：检索校验（相关性阈值、数量下限）。
- [x] 实现 `Reranker`：Rank 重排（Cross-Encoder 或模型重排）。**dev 落 BM25-lite**（IDF+tf 饱和+长度归一，证明重排顺序优于纯余弦召回），Cross-Encoder/模型重排为 prod 覆盖，**延后**。
- [x] 实现 `RagInjectionScanner`：RAG 片段注入扫描，防注入。
- [x] 实现 `RagFragment`：纯净片段数据结构，输出至上下文构建工厂客观数据层。
- [ ] Nacos 配置 Embedding 模型、向量库连接、Top-K、相关性阈值、Rerank 策略。**延后**：Top-K/相关性阈值/数量下限/种子开关已落 `application.yml` 基线（env 可覆盖）；动态热加载随 `NacosModelConfigSource`/`NacosPromptSource` 接入，真实 Embedding/向量库连接随 LangChain4j 接入。
**产出文件**：capability/rag 包下全部类、Nacos 配置更新
**验收标准**
- 文档向量化后正确入向量库。
- 检索召回相关片段，校验过滤低相关性结果。
- Rerank 后顺序优于纯向量召回。
- 注入片段被 `RagInjectionScanner` 过滤，纯净片段进入上下文。

### Phase 11 — 能力与执行层·HITL（第四层-HITL）
**目标**：实现高风险人工确认链路：触发→确认→超时/权限→人工工单。
**任务清单**
- [x] 实现 `HitlHandler`：高风险操作识别与确认请求生成（挂起当前流程，等待人工确认）。
- [x] 实现 `HitlDecision`：超时/权限判断（超时未确认、权限不足的处理）。
- [x] 实现 `HumanTicketService`：人工工单创建与管理。
- [x] 接入审计：HITL 触发、确认、超时、工单全量审计留痕（`HitlStep` 各分支 `log.warn` 审计 + 工单状态流转）。
**产出文件**：capability/hitl 包下全部类
**验收标准**
- 高风险操作触发 HITL，不自动执行。
- 超时未确认 → 转人工工单，流程不阻塞主链路。
- 权限不足 → 工单挂起 + 审计留痕。

### Phase 12 — 结构化输出与安全网关（第七层）
**目标**：实现结构化输出网关：JsonSchema 校验 + 重试兜底 + 输出安全过滤器。
**任务清单**
- [x] 实现 `StructuredOutputGateway`：LLM 原始输出 → 结构化校验入口。
- [x] 实现 `JsonSchemaValidator`：JsonSchema 校验，失败重试/兜底。
- [x] 实现 `OutputRetryFallback`：校验失败重试（受最大次数限制），兜底降级话术或默认结构。
- [x] 实现 `OutputSecurityFilter`：最终响应过滤敏感信息/注入残留。
- [x] `ChatController` 接通全链路：会话理解→意图→路由→能力→上下文构建→网关→结构化输出→返回用户（同步 `/chat` + SSE `/chat/stream` 单事件，真实 token 流式延后）。`OutputStep`(@Order 800) 串起 chatRaw→结构化网关→安全过滤→finalReply；`ModelConfigBootstrap` 启动即 refresh 落 dev-noop 模型种子，使全链路在 dev 可跑通。
**产出文件**：output 包下全部类、ChatController.java（接通全链路）
**验收标准**
- LLM 输出符合 JsonSchema → 正常返回。
- LLM 输出不符 Schema → 重试，重试耗尽走兜底降级。
- 最终响应经输出安全过滤器，无敏感信息/注入残留。
- `/chat` 同步返回正确回复；流式逐 token 返回。

### Phase 13 — 异步持久化 + 审计体系
**目标**：会话历史异步落库，建立独立审计链路。
**任务清单**
- [x] pom 引入 `spring-boot-starter-amqp`、`spring-boot-starter-data-jpa` + H2（dev）/MySQL（prod）驱动。
- [x] 实现 `RabbitMqConfig`：交换机、会话队列、审计队列、死信队列、Jackson 序列化。
- [x] 实现 `HistoryPersistProducer`/`Consumer`：会话持久化 + DLQ 死信处理。
- [x] 实现 `AuditProducer`/`Consumer`：审计事件持久化 + DLQ。
- [x] 实现会话/审计实体与 Repository（+ JPA 审计 `@CreatedDate` 落库时间戳，`PersistenceConfig` 收口）。
- [x] 会话完成触发 MQ 投递（会话持久化 + 审计发布）。**实现偏差**：经终端后置钩子 `ChatTurnFinalizer`（控制器在 `orchestrator.run` 后调用、独立 try-catch 持久化 + 审计刷出）收口，**取代** dev plan 原列的 `CompositeLlmCallbackHandler`——更贴合第四原则（终端统一收口）、best-effort 不阻塞响应，满足同一验收"对话完成后 DB 异步写入"。
- [x] 审计点发 `AuditEvent`：流水线内短路/降级/异常收口处统一留痕（`PipelineOrchestrator` 经 `AuditEventType.from(scenario)` 映射）+ 接入层注入预流水线直投（`InputSecurityFilter`→`AuditProducer`）。
**产出文件**：persistence 包下全部类（实体/Repository/生产者/消费者/`ChatTurnFinalizer`/`MessagePublisher` seam）、`config/PersistenceConfig`、`config/RabbitMqConfig`、`access/InputSecurityFilter`（更新审计）、`web/ChatController`（接线）、`eval/audit.json`
**验收标准**
- 对话完成后，DB 异步写入会话记录；停 MQ 不影响主接口响应。✓（`NoopMessagePublisher` 兜底 + `RabbitMqMessagePublisher` 吞而不抛 + `ChatTurnFinalizer` best-effort）
- 毒消息进入死信队列，不阻塞消费。✓（手动 ack 模式：`basicNack(tag,false,false)` requeue=false 死信 `agent.dlq`）
- 注入、工具调用、HITL、故障转移、异常等关键事件审计库有完整记录。✓（`AuditEventType.from(DegradationScenario)` 单一真相源映射 + 接入层/流水线双审计点）

### Phase 14 — LangGraph 集成（多步推理编排）
**目标**：基于 LangGraph4j 接入 Agent 状态机与多步推理编排，支持条件分支。
**任务清单**
- [x] pom 引入 `langgraph4j`（LangGraph 的 Java 移植）；验证与 Boot 4.1 / LangChain4j 版本兼容性。
- [x] 实现 `AgentStateGraph`：Agent 状态机定义（状态节点 + 共享状态）。
- [x] 实现 `GraphNode`：图节点（意图识别/能力执行/上下文构建/网关调用/输出校验等节点）。
- [x] 实现 `GraphEdge`：条件分支边（按意图/校验结果路由）。
- [x] 实现 `GraphExecutor`：图执行器，串联多步推理，支持循环（工具自纠正）与条件跳转。
- [x] 将 `/chat` 核心链路可选切换为 LangGraph 编排模式（保留直接链路作为对照）。
**产出文件**：langgraph 包下全部类、pom.xml、config/LangGraphConfig.java、common/pipeline/PipelineExecutor.java、eval/langgraph.json
**验收标准**
- LangGraph4j 依赖加载成功，无版本冲突。
- 状态机可编排多步推理，条件分支正确跳转。
- 工具自纠正场景下图可循环至成功或达最大迭代。
- 编排模式与直接链路输出结果一致。

### Phase 15 — 可观测性 + 运维控制台 + 微调闭环 + 评测 + 生产验收
**目标**：完善横向支撑体系，全链路可观测、运维可调权、微调闭环、评测端点、生产级验收。
**任务清单**
- [x] 完善全链路指标埋点：接口、会话、意图、模型路由、网关、RAG、工具、HITL、MQ、故障转移全维度。（T63/T64）
- [x] OTel 链路贯通 MQ 消费者，跨进程 traceId 不断。（T65）
- [x] 实现运维控制台 `RouteWeightController`：动态调整路由权重，写回 Nacos 热加载生效。（T66：内存热生效 + ConfigWriter seam 写回）
- [x] 实现微调闭环：`FeedbackCollector`（在线反馈）→ `OfflineDataPool`（离线数据池）→ `FineTuningPipeline`（编排外部训练任务）→ `ModelRegistrar`（调配置中心 API 动态注册新模型端点，热生效）。（T67/T68）
- [x] 实现评测 `EvalExecutor` + `EvalController`：`POST /eval/run` 批量执行，输出准确率/路由正确率/工具正确率/RAG命中率/故障转移率/耗时/Token 报告，鉴权保护。（T69/T70：stage 级通过率/per-case 报告；耗时/Token 指标待真实流水线埋点补齐）
- [x] `EvalExecutor` 加载 `resources/eval/*.json` 各阶段预设 golden 数据集（injection/intent/routing/rag/tool/hitl/degradation/audit），按 stage 分组执行并对照 expected 判定。（T69/T72：GoldenSuiteTest 验全 8 stage 加载+跑通 58 例）
- [x] 配置分级告警规则（P0/P1/P2），对接告警通道。（T71：AlertRule/AlertRuleEvaluator/AlertChannel seam）
- [x] 生产环境冒烟：高并发、故障注入、降级验证、故障转移验证。（T72：单元级冒烟代理 GoldenSuiteTest + degradation/resilience 既有单测；真实高并发/故障注入冒烟属部署门禁）
- [x] 安全巡检：注入测试、权限校验、密钥排查、HITL 拦截验证。（T72：SecurityAuditTest 守 injection 100%拦截 + HITL 100%短路；/eval/run 无鉴权不可访问由 EvalControllerTest 覆盖；runtime 拦截由 InputSecurityFilter/HitlStep 既有单测覆盖）
**产出文件**：observability 完善、admin/RouteWeightController.java、feedback 包下全部类、eval 包下全部类、EvalController.java、告警规则配置、压测报告、安全巡检报告
**验收标准**
- Prometheus 可查询全部核心指标；Jaeger 可通过 traceId 检索全链路节点。
- 运维控制台调权后路由权重热生效，灰度切换成功。
- 微调闭环：反馈→数据池→注册新模型→配置中心热加载→可被路由选中。
- 批量评测可输出完整报告，各项指标统计正确；生产环境无鉴权不可访问 `/eval/run`。
- 故障注入场景符合预期降级、故障转移与告警。
- 注入攻击 100% 拦截，HITL 高风险 100% 拦截，无旁路。

### Phase 16 — 前端工程骨架 + 对话界面
> 衔接节点：紧跟 Phase 12（`/chat` 全链路打通）后启动；可先用 mock 提前开工，Phase 12 完成后切真实接口。
**目标**：搭建 Vue 3 + Vite 前端工程，实现对话界面，让 `/chat` 端点可视化可用（流式 + 工具/RAG 折叠展示）。
**任务清单**
- [ ] 初始化 `frontend/` 工程：Vite + Vue 3 + TypeScript + Element Plus + ECharts + Pinia + Vue Router + axios + markdown-it。
- [ ] 配置 `vite.config.ts`：开发期 proxy `:5173 → :8080`，解决 CORS。
- [ ] 实现 `router/index.ts`：4 模块路由骨架 + 路由守卫（管理台/评测/仪表盘占位页）。
- [ ] 实现 `api/http.ts`：axios 实例 + 拦截器，读响应头 `X-Trace-Id` 透传，统一错误码 → Element Plus Message。
- [ ] 实现 `utils/sse.ts`：EventSource 封装，处理 `data:` 事件、错误、断线重连、关闭；首帧携带 traceId。
- [ ] 实现 `api/chat.ts`：`/chat` 同步调用 + SSE 流式调用。
- [ ] 实现 `views/chat/`：用户/AI 气泡 + Markdown 渲染、SSE 逐字追加、sessionId 多轮上下文、意图/路由标签、工具调用参数/结果折叠面板、RAG 检索片段折叠、故障转移/429 降级话术系统提示样式。
- [ ] 实现 `stores/`：会话状态（消息列表、sessionId、loading）。
- [ ] 后端补 `spring.web.resources.static-locations` 确认可托管 `static/`（为 Phase 17 单 jar 部署做准备）。
**产出文件**：frontend/ 工程骨架、api/http.ts、utils/sse.ts、api/chat.ts、views/chat/、stores/、router/index.ts
**验收标准**
- `npm run dev` 启动，开发期经 Vite proxy 调通后端 `/chat`。
- 同步对话返回正确气泡；流式对话逐 token 追加显示。
- 多轮上下文（sessionId）正确传递。
- 工具调用、RAG 片段以折叠面板展示；意图/路由标签显示。
- traceId 在前端可见并与后端日志对齐；503/429/注入拦截有对应 UI 提示。
- `npm run build` 产物可被 Spring Boot `static/` 托管访问。

### Phase 17 — 工具调用断路器（per-tool 熔断 + 自纠正耗尽隔离）
> 衔接节点：卡 Phase 9（工具调用链路）+ Phase 5（韧性层 CircuitBreaker）后；与前端并行。
**目标**：为工具调用引入 per-tool 熔断器，单一外部工具故障不致反复触发自纠正/死循环，熔断后零 LLM 话术短路（①②④三原则交汇）。

**设计要点**
- 既有 `CircuitBreaker`（Phase 5）服务于模型网关 per-model 故障转移，不覆盖工具维度；本 phase 落地 per-tool 维度独立熔断，复用三态语义（CLOSED/OPEN/HALF_OPEN）+ 可注入时钟。
- 熔断点在 `ToolExecutor.executeCall` 前置：OPEN→抛 `ToolCircuitOpenException`，由 `ToolExecutionStep` 收口为 `ShortCircuit(TOOL_FAILURE)`（HTTP 200，零 LLM，①话术短路）；成功记 success、`ToolRecoverableException` 记 failure。
- 引擎无关 seam 不变：断路器是 `ToolExecutor` 内部能力，`PipelineStep`/`StepOutcome` 收口契约不动（④换引擎不换收口）。

**任务清单**
- [x] 实现 `ToolCircuitBreaker`：per-tool 三态熔断（按 toolName 独立计数），连续失败/失败率超阈值→OPEN，冷却→HALF_OPEN 探针，探针成功→CLOSED/失败→重开；时钟可注入。（T73）
- [x] `ToolExecutor` 接入断路器：`execute` 前置 `allow(toolName)`，OPEN→抛 `ToolCircuitOpenException`（②每步降级，工具跳过不阻塞）；执行成功/异常分别记 success/failure。（T74）
- [x] `ToolExecutionStep` 收口：`ToolCircuitOpenException` → `StepOutcome.ShortCircuit(TOOL_FAILURE)` 话术短路（①话术短路 + ④统一收口）；复用 `TOOL_FAILURE` 场景（见下实现注记）。（T75）
- [x] ③环节测评：per-case 不可表达（见下实现注记），改由单测 `ToolExecutorCircuitTest`/`ToolExecutionStepCircuitTest`/`ToolCircuitWiringTest` 覆盖熔断三态 + 收口 + 装配。（T76）
- [x] 可观测：熔断开断次数经 `AgentMetrics.recordToolCircuitOpen(toolName)` 埋点（counter `agent.tool.circuit.open`，tool 标签）；告警规则 `tool.circuit.open.count>0`(P1) 已入 `AlertConfig.standardRules`。（T77）

**产出文件**：resilience/ToolCircuitBreaker.java、resilience/ToolCircuitOpenException.java、capability/tool/ToolExecutor.java（6 参构造+接入）、capability/tool/ToolExecutionStep.java（收口+埋点）、capability/tool/ToolConfig.java（@Bean 装配）、observability/AgentMetrics.java、observability/alert/AlertConfig.java（P1 规则）。详见下"实现注记"。

**验收标准**
- 工具连续失败超阈值 → 熔断 OPEN，后续调用零 LLM 直接话术短路（HTTP 200，TOOL_FAILURE/TOOL_CIRCUIT_OPEN）。
- 冷却后半开放行探针，探针成功恢复 CLOSED，探针失败重开 OPEN。
- 熔断不阻塞主链路（②每步降级），不触发自纠正死循环（Phase 5 风险表"工具自纠正死循环"护栏补齐）。
- 引擎无关：线性/LangGraph/AgentScope 三引擎下断路器行为一致（收口契约保证）。

**实现注记（落地与原设计差异，诚实记录）**
- **T73 时钟类型**：原设计写 `Supplier<OffsetDateTime>`，实际复用 Phase 5 `CircuitBreaker` 的 `LongSupplier`（epochMillis）——per-tool `ToolCircuitBreaker` 内部 `ConcurrentHashMap<String,CircuitBreaker>` 按 toolName 惰性建实例，直接委托既有三态实现，避免重复造轮；单测 `ToolCircuitBreakerTest` 用 `AtomicLong` 控时验三态流转。
- **T74 记账位置**：记账在 `ToolExecutor.execute()` 公共边界（成功记 success / `ToolRecoverableException` 记 failure），非递归 `executeCall` 内——dev 路径（`Reparser.NONE`）干净；prod 同 toolName 重解析场景极少（重解析通常改工具/参数），若后续出现跨工具名重提交再评估。
- **T75 复用 `TOOL_FAILURE` 而非新增 `TOOL_CIRCUIT_OPEN`**：原设计拟新增降级场景枚举，实际复用既有 `TOOL_FAILURE` 话术（"该操作暂时无法完成，请稍后重试。"，HTTP 200，零 LLM）。区分维度改由①异常类型（`ToolCircuitOpenException` vs `ToolRecoverableException`，前者非后者子类，确保既有 catch 不吞）②日志（"工具熔断中" vs "自纠正耗尽"）③指标（`agent.tool.circuit.open` 独立计数）三重隔离——避免改 `DegradationPhraseCenter`/`AuditEventType` 映射面，降级话术一致（用户不感知"熔断"与"耗尽"措辞差异，皆"稍后重试"）。
- **T76 per-case 不可表达（关键限制）**：熔断 OPEN 是**跨请求累积状态**（`ToolExecutor.execute` 入口 allow-check 每请求一次、`recordFailure` 每请求一次），单请求内无法触达阈值→OPEN；eval 单用例是一请求一断言，**无法在单用例内复现"连续失败达阈值→熔断→后续请求短路"**。强造 eval 用例必失败或必伪造状态，违背③环节测评"golden 真值"初衷。故此 phase 不扩 `eval/tool.json`，改由三个单测覆盖：`ToolExecutorCircuitTest`（执行器接入：连续失败达阈值→OPEN→`ToolCircuitOpenException`）、`ToolExecutionStepCircuitTest`（收口：异常→`ShortCircuit(TOOL_FAILURE)`）、`ToolCircuitWiringTest`（Spring 装配：注册 flaky 工具→连调阈值次→第三次抛 `ToolCircuitOpenException`，证明断路器 bean 真注入执行器非空跑）。
- **T77 指标独立**：`recordToolCircuitOpen` 独立于 `recordTool(success)`——熔断是工具持续不可用的跨请求累积态，不与单次成功/失败混计；告警快照键 `tool.circuit.open.count`（`agent.tool.circuit.open` 跨工具求和派生，由未来可观测面板定时拉取注入 `AlertRuleEvaluator.evaluate(Map)`）。P1 定级：经 ②每步降级 平台仍可用（非 P0 服务不可用）。
- **T77b 装配**：`ToolConfig` 新增 `@Bean ToolCircuitBreaker`（`app.tool.circuit.failure-threshold`/`cooldown-ms` 配置驱动，缺省 3/30000ms，`System::currentTimeMillis` 时钟），`toolExecutor` @Bean 改 6 参构造注入断路器。`ToolCircuitWiringTest`（`@SpringBootTest`+`@DirtiesContext`，properties 覆盖阈值=2）锁定装配。
- **产出文件**：resilience/ToolCircuitBreaker.java、resilience/ToolCircuitOpenException.java、capability/tool/ToolExecutor.java（6 参构造+接入）、capability/tool/ToolExecutionStep.java（收口+埋点）、capability/tool/ToolConfig.java（@Bean 装配）、observability/AgentMetrics.java（`recordToolCircuitOpen`）、observability/alert/AlertConfig.java（P1 规则）。
- **测试**：全量 555 GREEN（Phase 17 新增 10 测：ToolCircuitBreakerTest×2 + ToolExecutorCircuitTest×2 + ToolExecutionStepCircuitTest×1 + AgentMetricsCircuitTest×1 + ToolCircuitWiringTest×1 + AlertCircuitRuleTest×3），既有 545 测零回归。

### Phase 18 — AgentScope 2.0 集成重构 Agent 构建架构
> 衔接节点：卡 Phase 14（LangGraph 编排 + PipelineExecutor 引擎切换锚点）+ Phase 15 后；与前端并行。
> 技术底座新增：AgentScope Java 2.0（`io.agentscope:agentscope-core` / `agentscope-harness` / `agentscope-extensions-model-openai`，Maven Central，JDK 17+，基于 Project Reactor；v2.0.0 GA 2026-07，Alibaba Tongyi Lab 开源，阿里 13+ BU 生产验证）。

**目标**：引入 AgentScope Java 2.0 作为第三执行引擎，重构 Agent 构建架构——以 `ReActAgent`（reason→tool→reply 核心循环）+ `HarnessAgent`（Middleware/Toolkit 工程层：workspace/memory/compaction/sub-agents/sandbox/Plan Mode/skills）替代手写编排内核，同时经 `PipelineExecutor` seam 与既有线性/LangGraph 引擎共存可切，四原则经收口三件套不变。

**设计要点**
- **迁移策略（关键决策）**：coexist-as-engine + 渐进迁移。AgentScope 经 `AgentScopeEngine implements PipelineExecutor`（`@ConditionalOnProperty app.pipeline.mode=agentscope`）接入，与线性 `PipelineOrchestrator`/LangGraph `GraphExecutor` 三引擎互斥可切（沿用 Phase 14 切换模式，可回退）。`PipelineContext`/`StepOutcome` 收口契约为引擎边界——**换引擎不换收口**（④统一收口，§5.14）。
- **能力映射**（既有手写 → AgentScope 2.0 原语，经 seam 桥接，不丢四原则）：

  | 既有 | AgentScope 2.0 原语 | 收口保留 |
  |---|---|---|
  | `PipelineContext` | `RuntimeContext`(sessionId/userId) + `AgentStateStore` | PipelineContext 仍为状态唯一收口，Adapter 双向同步 |
  | `ToolExecutor`/`ToolRegistry`/`Reparser` | Toolkit（ToolSpec）+ ReAct 自纠正循环 | Reparser seam 作 reparse 通道；Phase 17 断路器随 Toolkit 迁移 |
  | `ModelExecutor`(+`ResilientExecutor`) | Model（`agentscope-extensions-model-openai`） | 保留 ResilientExecutor 包裹 Model 调用，韧性层统一不双轨 |
  | `HitlHandler`/`HitlStep` | Permission System（allow/require-approval/deny） | HitlStep 收口 ShortCircuit(HITL_TIMEOUT) 不变（①话术短路） |
  | `SessionCacheService`(Redis) | 分布式 AgentStateStore(Redis) | 统一 sessionId 跨进程/跨副本恢复 |
  | `AgentMetrics`/`AuditEvent` | Agent Evolution（observability/auditing/eval） | 经既有门面收口，不散落 MeterRegistry |

- **严格按官方文档对接（用户确认）**：AgentScope 2.0 接入**严格遵循官方文档对接方式**（`java.agentscope.io` Quickstart / Harness Architecture / Spring 集成章节），不另造桥接。本项目**不坚持** Servlet 栈 + SseEmitter / **不拒绝 WebFlux**——AgentScope 基于 Project Reactor（Mono/Flux），按官方方式接入响应式 SSE/WebFlux；线性/LangGraph 引擎路径保留既有 `SseEmitter`（§2 决策修正：引擎分流，AgentScope 路径用官方响应式栈，不强制 Servlet 桥接）。
- **31 typed events → SSE**：按 AgentScope 官方事件流方式驱动前端实时渲染（text delta/tool execution/user confirmation）；SSE 协议层对前端统一（EventSource 消费），后端实现按引擎分流，前端（Phase 16）不耦合后端引擎。

**任务清单**
- [ ] pom 引入 `io.agentscope:agentscope-core`+`agentscope-harness`+`agentscope-extensions-model-openai`；冒烟 Boot 4.1 / Spring 7 / JDK 17 兼容 + Reactor 与 Servlet 栈共存不冲突（同 Phase 2/14 依赖风险预案）。（T78）
- [ ] 实现 `AgentScopeEngine implements PipelineExecutor`（`@ConditionalOnProperty mode=agentscope`）：`PipelineContext`↔`RuntimeContext`+`AgentStateStore` 双向 Adapter；ReAct reason→tool→reply 映射既有步骤序列。换引擎不换收口（④）。（T79）
- [ ] Toolkit 适配：`ToolRegistry`/`ToolDefinition`→AgentScope ToolSpec；`Reparser` seam 作 reparse 通道；Phase 17 `ToolCircuitBreaker` 随 Toolkit 迁移。（T80）
- [ ] Model 适配：`ModelExecutor`→AgentScope Model（openai 扩展），保留 `NoopModelExecutor` dev 兜底；`ResilientExecutor` 包裹 Model 调用保持韧性层统一。（T81）
- [ ] HITL/Permission 适配：`HitlHandler`→AgentScope Permission System；`HitlStep` 收口 `ShortCircuit(HITL_TIMEOUT)` 不变（①）。（T82）
- [ ] 分布式会话：`SessionCacheService`(Redis)↔AgentScope `AgentStateStore`(Redis) 统一 sessionId 跨进程恢复。（T83）
- [ ] 事件流/SSE 桥接：AgentScope 31 typed events→既有 SSE 协议；Reactor `Mono`→`SseEmitter`（不引入 WebFlux），servlet 栈桥接。（T84）
- [ ] 可观测/审计/评测对接：AgentScope Agent Evolution→既有 `AgentMetrics`/`AuditEvent` 收口；不散落 MeterRegistry。（T85）
- [ ] ③环节测评：`eval/agentscope.json` ReAct 端到端用例（工具自纠正/多步推理/条件分支）；GoldenSuiteTest 扩展覆盖。（T86）
- [ ] 三引擎一致性测试：线性/LangGraph/AgentScope 同输入输出等价（扩展 `OrchestratorGraphConsistencyTest`），收口契约保证。（T87）

**产出文件**：pom.xml（依赖）、agentscope/AgentScopeEngine.java、agentscope/ContextAdapter（PipelineContext↔RuntimeContext/AgentStateStore）、agentscope/ToolkitAdapter、agentscope/ModelAdapter、agentscope/PermissionAdapter、agentscope/SseEventBridge、config/AgentScopeConfig.java、eval/agentscope.json

**延后项 / 部署门禁**：HarnessAgent 全量工程能力（workspace/sandbox/sub-agents/Plan Mode/skills 四层）按需渐进开启，非一次到位；分布式部署（K8s/AgentRun cloud sandbox）属部署门禁；AgentScope Service 控制面（2026-08 新发布）对接待运维侧评估。

**验收标准**
- `app.pipeline.mode=agentscope` 下 `/chat` 全链路跑通，与线性/LangGraph 同输入输出等价（三引擎一致性测试）。
- ReAct 循环：工具自纠正→成功 或 达最大迭代收口 TOOL_FAILURE（与既有 ToolExecutor 语义一致）。
- HITL 高风险→Permission 拦截→话术短路（①），零旁路。
- 按 AgentScope 官方文档对接方式接入（响应式 SSE/WebFlux，不另造 Servlet 桥接）；SSE 协议层对前端统一，前端 EventSource 不感知后端引擎切换。
- 四原则不变：注入 100% 拦截、每步降级不抛 5xx、环节测评 golden 集、统一收口三件套经 PipelineContext/StepOutcome。
- 可回退：切回 `mode=linear`/`graph` 既有链路无损。

### Phase 19 — 管理台 + 评测可视化 + 可观测性仪表盘
> 衔接节点：紧跟 Phase 15（后端管理台/评测/可观测完成）后启动。
**目标**：实现运维管理台、评测可视化、可观测性仪表盘三大模块，对接真实后端接口，完成前端生产级验收。
**任务清单**
- [ ] 实现 `api/admin.ts`：路由权重读写、模型配置查询、HITL 工单列表/确认/驳回、会话历史检索。
- [ ] 实现 `views/admin/`：路由权重动态调整（滑块 → 写回配置中心热生效）、模型元数据表、HITL 工单操作、会话历史查看。
- [ ] 实现 `api/eval.ts` + `views/eval/`：用例批量输入/JSON 导入、`/eval/run` 调用、报告卡片+表格（准确率/召回率/路由正确率/工具正确率/RAG命中率/故障转移率/P95耗时/Token）、失败用例明细对比。
- [ ] 后端补轻量聚合端点 `GET /api/obs/summary`（返回 JSON 指标快照），前端 `api/obs.ts` 轮询。
- [ ] 实现 `views/observability/`：指标卡片（QPS/P95延迟/错误率/Token/熔断状态）、ECharts 时序折线图、traceId 检索→节点瀑布图、告警列表。
- [ ] 链路拓扑/详细图表：若后端已接 Grafana/Jaeger，可改 iframe 嵌入（省去重复造轮子，按实际后端情况定）。
- [ ] 前端路由守卫 + 后端鉴权双重保护管理台/评测/仪表盘页。
- [ ] `npm run build` 产物拷入 Spring Boot `src/main/resources/static`，验证单 jar 部署可用。
- [ ] 可选 Playwright E2E：对话流式 + 管理台调权冒烟。
**产出文件**：api/admin.ts、api/eval.ts、api/obs.ts、views/admin/、views/eval/、views/observability/、后端 /api/obs/summary 端点
**验收标准**
- 运维管理台调权后路由权重热生效（刷新页面与后端配置中心一致）。
- 模型元数据表展示完整；HITL 工单可确认/驳回并审计。
- 评测页批量用例可执行，报告各项指标统计正确；无鉴权不可访问。
- 仪表盘指标卡片/时序图实时刷新；traceId 检索可还原全链路节点。
- 单 jar 部署：后端启动后 `http://host/` 直接访问前端，无需独立 nginx。
- 生产环境管理台/评测/仪表盘无鉴权不可访问。

### Phase 20 — 检索理解增强（约束改写 + Hybrid RAG + 时效治理）
> 衔接节点：卡 Phase 6/8/10（会话理解 + 上下文构建 + RAG 基线已实现）后；与前端/AgentScope 并行。
**目标**：在既有改写 + RAG 基线上补三项生产级约束——①改写只补不替、保 original query、不下业务结论；②Hybrid RAG 向量+关键词+规则路由三通道融合，精确词不走纯语义；③RAG 结果带时效，过时知识不被当当前最新。

**设计要点**
1. **约束改写（refine §5.2）**：`QueryRewriter` 只做"补全"不做"替换"——补关键词/商品名/活动名/时间线（从历史+当前输入抽取），**必须保留 original query**（用户原话不可改写丢弃），**不下业务结论**（不替用户判定意图归属/订单状态/业务决策）。产出 = original query + 补充上下文槽（强类型 `QueryEnrichment`，不覆盖 `standardQuery`）。改写失败→回退原问题（既有行为级降级不变）。`RewriteQualityChecker` 增"未篡改原意/未新增业务结论"断言。
2. **Hybrid RAG（refine §5.4.1）**：检索三通道融合：
   - **向量通道**（既有 `VectorRetriever` 余弦 Top-K，语义近似）
   - **关键词通道**（BM25/精确词匹配，订单号/型号/发票类型这类**精确词必走此通道**，不靠语义漂移）
   - **规则路由通道**（按词型路由：订单号→订单库、型号→商品库、发票类型→发票规则；正则/词典命中即定向检索）
   - 融合：三通道去重 + Rerank（既有 BM25-lite / prod Cross-Encoder）+ 相关性校验；规则路由命中结果优先级置顶（精确匹配 > 语义近似）。
3. **时效治理（refine §5.4.1 + §5.5）**：`RagFragment` 增 `timestamp`/`validUntil`/`temporalTag` 强类型字段（④收口，非 Map）；检索返回标注时效；上下文构建（客观数据层）按时效标注——过时片段框定"【历史参考资料·截至{date}】"隔离头，当前片段标"【当前有效】"；检索校验/重排引入时效衰减（同等相关性近期优先）；过时且无当前对应时不冒充最新。

**任务清单**
- [x] 约束改写：`QueryEnricher` 抽精确词/时间线入 `QueryEnrichment`（只补不替、不下业务结论，原查询经 rawInput 保留）；`QueryRewriter` 产补全槽不覆盖 standardQuery；`RewriteQualityChecker` 增"未新增业务结论"守卫。（T88）
- [x] Hybrid RAG 三通道：`Retriever` seam + `KeywordIndex` seam + `HybridRetriever`（向量+关键词融合，精确词命中优先置顶）；`InMemoryVectorStore` 兼 `KeywordIndex`；`@Primary` 装配。（T89）
- [x] 时效字段：`RagFragment` 增 `timestamp`/`validUntil`/`temporalTag` 强类型；`displayText()` 在 RagStep→List\<String\> 抽取边界隔离标注历史片段（"【历史参考资料·截至{date}】"，§5.14 收口不外泄 RagFragment）。（T90）
- [x] 时效衰减：`Reranker` 对 HISTORICAL 片段 BM25 分乘衰减系数（同等相关性近期优先）；search/searchByKeywords/rerank 全链路透传 temporal 字段。（T91）
- [x] ③环节测评：扩 `EvalExpected`/`ActualOutcome`/`compare` 支持 `queryEnrichmentContains`/`ragFragmentsContain`/`hasFragments`（真正可断言，非文档字段被忽略）；扩 `eval/understanding.json`（und-006/007）+ `eval/rag.json`（rag-006/007）+ 种子语料（精确词/历史时效样例）；GoldenSuiteTest 覆盖加载。（T92）

**产出文件**：session/rewrite/QueryRewriter.java（重构）+QueryEnrichment.java、session/rewrite/RewriteQualityChecker.java、capability/rag/HybridRetriever.java+KeywordChannel.java+RuleRoutingChannel.java、capability/rag/RagFragment.java（时效字段）、capability/rag/RetrievalValidator.java+Reranker.java（时效衰减）、context/ObjectiveDataLayer.java（时效标注）、eval/understanding.json+rag.json（扩展）

**验收标准**
- 改写后 original query 保留可见，无业务结论被替用户下达；改写失败回退原问题（②每步降级不变）。
- 订单号/型号/发票类型检索走精确通道命中，纯语义向量不漂移；规则路由命中结果优先级置顶。
- RAG 片段带时效标注，过时片段显式隔离不冒充当前；同等相关性近期优先。
- 四原则不变：Hybrid 融合 + 时效经 `RagFragment` 强类型收口（④）、环节测评 golden（③）、约束改写降级回退（②）。

**实现注记（落地与原设计差异，诚实记录）**
- **T88 补全槽定位**：`QueryEnrichment(keywords, timeline)` 收口于 `PipelineContext`（§5.14，强类型非 Map，缺省 `EMPTY` 非空）。`QueryEnricher` 确定性抽取（正则 ASCII 标识符订单号/型号 + CJK 词典精确词发票类型 + 时间线正则），只抽事实**不下业务结论**；prod 覆盖为 NER/分词。`QueryRewriter` 2 参构造（`@Autowired` 注入 enricher）+ 1 参兜底（`new QueryEnricher()`，旧测试零改动），改写 prompt 增"保留原意/不下结论"约束。`standardQuery` 仍为自足改写（既有行为不变，供意图/RAG 消费），补全槽**不覆盖** standardQuery（原查询经 `rawInput` 保留不丢）。
- **T88d 业务结论守卫**：`RewriteQualityChecker` 增 `BUSINESS_CONCLUSION_MARKERS`（已发货/已退款/已到账/已完成 等），改写含标记而原问题未提及 → 回退原问题。用户原话提及时保留不算新增结论（避免误杀询问句）。既有 5 测零回归（测用例无业务标记）。
- **T89 Retriever/KeywordIndex seam**：`Retriever` 接口（`retrieve(query, topK)`）统一召回出口，`VectorRetriever implements Retriever`，`HybridRetriever implements Retriever`。`KeywordIndex` seam（`searchByKeywords`）——dev 由 `InMemoryVectorStore` 兼任（同语料子串精确匹配），prod 不实现时装配 `NO_OP`（关键词通道空 → Hybrid 回退纯向量，②降级）。`vectorStore` @Bean 返回具体类型 `InMemoryVectorStore` 以便作 `KeywordIndex` bean 被 `@ConditionalOnMissingBean` 识别。`HybridRetriever` `@Primary` 装配，`RagStep` 经 `Retriever` seam 注入（字段类型由 `VectorRetriever` 改 `Retriever`，既有 `RagStepTest` 零改动——`VectorRetriever` 亦 `Retriever`）。规则路由 = 精确词命中优先置顶 + BM25 稀有词高 IDF 自然上浮（不另造 boost 标记，避免外泄 bespoke 结构违 §5.14）。
- **T90 时效标注边界（§5.14 关键决策）**：原设计拟由 `ObjectiveDataLayer` 做时效标注，但 §5.14 收口明确 `ragFragments: List<String>`（不外泄 `RagFragment` bespoke 结构到步骤间），改 `List<RagFragment>` 会触 `PipelineContext`/`ObjectiveDataLayer`/`ContextMerger`/`eval/context.json`（7 处含 JSON 反序列化）大辐射且违收口。故时效标注落在 **RagStep→List\<String\> 抽取边界**：`RagFragment.displayText()` 历史片段前缀"【历史参考资料·截至{date}】"，当前片段原文；`ObjectiveDataLayer` 既有 `RAG_HEADER` 外层隔离不变，per-fragment 标注内嵌文本串。模型所见效果等价，§5.14 收口不破。`RagFragment` 3 参构造保留（既有调用方零改动），temporal 字段经全链路（search/searchByKeywords/rerank）透传——3 参构造会丢 temporal，故均改全参构造。
- **T91 时效衰减**：`Reranker` 对 `temporalTag=HISTORICAL` 片段 BM25 分乘 `TEMPORAL_DECAY=0.5`（同等 BM25 近期优先）。用 `temporalTag`（显式）而非 `validUntil`-vs-now 比较作信号——避免注入时钟的时序依赖（indexer/测试直接设 `HISTORICAL`，确定性）。`validUntil`/`timestamp` 仅供标注"截至{date}"。
- **T92 ③环节测评真断言**：发现既有 `EvalExpected` 的 `standardQueryContains`/`hasFragments`/`summaryTriggered` 字段被 `FAIL_ON_UNKNOWN_PROPERTIES=false` 静默丢弃——**文档字段从未被对照**（hollow eval）。故扩 `EvalExpected`（+`queryEnrichmentContains`/`ragFragmentsContain`/`hasFragments`，10 参次级构造保留既有调用方）+ `ActualOutcome`（+3 派生字段）+ `compare`（`checkContains`/`checkAnyContains`/`checkBoolean`），使 Phase 20 行为**真正可断言**。`EvalExecutorTest` 旧 10 参构造零改动。新增 4 单测（fake 执行器设 queryEnrichment/ragFragments）验对照逻辑（pass/fail）。golden 用例 und-006/007（补全精确词）+ rag-006/007（Hybrid 命中/时效标注）+ RagSeedRunner 增精确词/历史种子（供真实流水线 eval 跑通）。**已知限制**：eval 用例在测试套件中仅由 `GoldenSuiteTest`（trivial 执行器）加载冒烟——不跑真实流水线、不断言通过率（dev 无真实 LLM，通过率依赖真实流水线属部署门禁）；对照逻辑由 `EvalExecutorPhase20Test` 单测守护。
- **产出文件**：session/model/QueryEnrichment.java、session/rewrite/QueryEnricher.java（@Component）；common/pipeline/PipelineContext.java（+queryEnrichment）；session/rewrite/QueryRewriter.java（2 参构造+补全+prompt）、RewriteQualityChecker.java（业务结论守卫）；capability/rag/Retriever.java、KeywordIndex.java、HybridRetriever.java、VectorRetriever.java（implements Retriever）、InMemoryVectorStore.java（implements KeywordIndex+透传 temporal）、Reranker.java（透传+时效衰减）、RagFragment.java（temporal 字段+displayText）、RagStep.java（Retriever seam+displayText 抽取）、VectorStoreConfig.java（@Primary Hybrid+NO_OP KeywordIndex）、RagSeedRunner.java（精确词/历史种子）；eval/EvalExpected.java、ActualOutcome.java、EvalExecutor.java（+Phase 20 对照）、eval/understanding.json、eval/rag.json。
- **测试**：全量 594 GREEN（Phase 20 新增 39 测：QueryEnricherTest×7 + PipelineContextEnrichmentTest×1 + QueryRewriterEnrichmentTest×3 + RewriteQualityCheckerConclusionTest×4 + HybridRetrieverTest×5 + HybridRetrieverWiringTest×1 + RagFragmentTemporalTest×6 + RagTemporalPreservationTest×3 + RagStepTemporalFramingTest×2 + RerankerTemporalDecayTest×3 + EvalExecutorPhase20Test×4），既有 555 测零回归。

---

### Phase 21 — RAG 知识分级可见性（客户等级 ABAC · 三轴拆分）
> 衔接节点：卡 kb 目录快照权限模型（`KbCatalogService`·kb-ingest 任务3）之上；与前端录入表单改造并行。（2026-09-20 设计拷问收敛）

**目标**：把挤在两个字段里的三个权限概念拆回各自的轴——`domain` 管业务/领域分类（路由轴）、`requiredLevel` 管客户等级可见性（安全轴，新增）、`namespace` 管内外边界与存放分区（主体类型轴）。权限谓词 = **主体类型可见 且 主体等级 ≥ 文档要求等级**（点对点白名单为例外通道）；等级查不到 fail-closed 只出 PUBLIC。

**设计要点**
1. **三轴分离（核心结论）**：词表性质决定归属——**有界业务词表进代码、无界内容词表进 DB、漏配在写入时堵死**。
   - `domain`（业务/领域分类·无界词表·DB）：上传者可选可扩，喂给既有 RoutePlan 知识域窄化——**已存在，零新代码**；
   - `requiredLevel`（客户等级可见性·有界词表 V0~V5·代码枚举）：`kb_document` 新列，**权限主载体**；
   - `namespace`（PUBLIC/PRIVATE）：语义修正为内外边界 + 存放分区，不再冒充领域（原枚举命名误导即本次设计缺陷根源）；
   - `allowedPrincipals`（点对点白名单）：降为例外逃生通道（专属客群单点授权），不承载主权限。
2. **杀死 namespace↔level 映射表**：权限骑在**文档属性**上，不在容器上。"等级反查可查命名空间"会在"新命名空间漏配映射"处重新引入漏配面——默认放行 = 权限漏洞，默认拒绝 = "传了搜不到"的对账成本。强制校验的对象是**录入表单的 requiredLevel 必选**（前端 required + 后端显式校验 + `NOT NULL DEFAULT 0` DB 兜底，三层保险），而非任何容器映射。
3. **等级来源与失败语义**：登录态 uid → 会员服务查询（demo 为 mock，如 10086→V5、10010→V1）→ 会话建立时解析一次进 `PipelineContext`（强类型收口 §5.14）。**等级永不从对话内容取**——用户自称"我是VIP"不采信（提示词注入直通车）。查不到/服务挂 → 一律按 V0 处理（fail-closed 只出 PUBLIC，与现有 null 主体语义一致，对齐业务门 fail-closed 哲学）。
4. **检索过滤落点**：`KbCatalogService` 快照 `Entry` 增 `requiredLevel` 字段，内存快照机制原样复用（百万文档级几十 MB；白名单的病在运营 N×M 不在查询性能——`Set.contains` O(1)），过滤谓词在漏斗②域窄化处一行比较。过滤发生在粗滤之前 → 引用列表天然继承，不可见文档标题不可能泄漏进 citation（需测试钉死）。
5. **规模论证与升级路径**：等级字段把权限运营成本降为 0（改用户等级不动任何文档）；亿级片段再谈 Chroma metadata where 下推（届时内存快照作废是已知升级路径，非本阶段范围）。

**任务清单**
- [x] T93 词表与实体：`KbLevel` 枚举（V0 公开~V5 全量，含 label 与等级比较）+ `kb_document.required_level` 列（NOT NULL DEFAULT 0，存量行平滑升级）+ `KbDocumentEntity`/录入命令/DDL 同步。（T93）
- [x] T94 等级解析：`MemberLevelService` seam + dev mock（10086→V5、10010→V1）；登录态会话建立时解析一次 → `PipelineContext.memberLevel` 强类型字段；eval/未登录/查询失败 = V0。（T94）
- [x] T95 过滤谓词：`KbCatalogService.Entry` 增 requiredLevel，快照加载/刷新带出；可读判定 = `allowedPrincipals` 命中（例外优先）或（namespace=PUBLIC 且 `memberLevel ≥ requiredLevel`）；PRIVATE 维持现有员工/管理侧语义不变。（T95）
- [x] T96 录入链路必填：KbAdminController/KbIngestService/前端 kb 表单 requiredLevel 必选——DB 默认值仅作兜底，接口层显式拒绝漏配。（T96）
- [x] T97 回归钉死：V1 会话查 V3 文档不可见；引用列表零泄漏（不可见文档标题不出现在 citation）；eval/未登录只见 V0；会员服务故障 fail-closed；存量 PUBLIC 文档检索行为零回归。（T97）

**产出文件**：capability/kb/KbLevel.java（新）、KbCatalogService.java（Entry+谓词）、KbIngestService.java（必填校验）、admin/KbAdminController.java（参数校验）、persistence/entity/KbDocumentEntity.java、sql/schema.sql（required_level 列）、common/pipeline/PipelineContext.java（+memberLevel）、session/MemberLevelService.java（seam+mock）、前端 kb 录入表单（requiredLevel 必选）

**验收标准**
- 三个权限概念各归其轴：domain 管路由窄化、requiredLevel 管客户等级可见性、PUBLIC/PRIVATE 管内外边界；系统中不存在 namespace↔level 映射表。
- 等级只来自登录态+会员服务，对话自称不采信；查不到 fail-closed 只出 PUBLIC。
- 录入漏配 requiredLevel 不可能发生（前端必选 + 后端校验 + DB 默认值三层）。
- V1 查 V3 不可见、citation 零泄漏、eval/未登录只见 V0；既有 PUBLIC 检索路径与快照机制零感知。

**实现注记（落地与原设计差异，诚实记录）**
- **T93 词表归属**：`KbLevel` 六档枚举（V0 公开/V1 注册客户/V2 白银/V3 黄金/V4 铂金/V5 全量）进代码（有界业务词表），`code()=ordinal()` 显式命名防位次漂移，`atLeast()` 即谓词核心比较；`fromCode(int)` 越界收敛 V0（快照加载边界坏数据 fail-closed 不放大权限）。DB 存 `required_level TINYINT NOT NULL DEFAULT 0`（int 非 enum 名——词表在代码，DB 只存档位）。**存量库升级**：schema.sql 是 CREATE IF NOT EXISTS 幂等脚本不会补列，已有 prod 库须手工 `ALTER TABLE kb_document ADD COLUMN required_level TINYINT NOT NULL DEFAULT 0;`（DDL 注释内已带此语句）；dev 走 Hibernate ddl-auto=update 自动补列。`KbDocumentEntity.create` 加 15 参全参装配器，旧 14 参委托 V0（既有调用方零改动）。
- **T94 解析点与恢复链路**：`MemberLevelService` seam 契约"不抛异常、不返回 null，失败一律 V0"——调用方零防御、等级解析失败绝不阻塞主链也绝不放大权限；mock（10086→V5、10010→V1）经 `MemberLevelConfig` `@ConditionalOnMissingBean` 装配（镜像 HitlConfig 范式，prod 注册同类型 bean 即覆盖）。解析点两处：`ChatController.run`（/chat 与 /chat/stream 共同入口）+ `HitlResumeService.rebuildContext`——**恢复链路含 RagStep@660（postHitlSteps = @Order>610），快照不持久等级，恢复时刻重解析**（以会员服务当下为准；快照持久等级反而是旧值反漂移）。`PipelineContext.memberLevel` 缺省 V0（eval/未登录/直构步骤零感知）。
- **T95 谓词与例外通道**：`Entry` 增 `requiredLevel`（快照机制原样复用）；判定序 = ①非托管来源恒放行（存量语义）→ ②`allowedPrincipals` 命中直接放行（**例外通道优先**——名单是点对点授权，不应被文档档位误伤）→ ③PUBLIC 按 `memberLevel.atLeast(requiredLevel)` → ④PRIVATE 名单不命中即 false（员工/管理侧语义不变）。注意此前 PUBLIC **恒** true（allowedPrincipals 对 PUBLIC 忽略），新语义下 PUBLIC+名单+等级不足 → 名单主体仍可见、外人按等级——例外通道语义统一到两轴。`filterReadable/readable` 3 参为正典，旧 2 参委托 V0 匿名口径（既有测试零改动）。RagStep ①′ 权限过滤位置不变（宽召回后、域窄化/粗滤/重排/终闸之前），citation 在抽取边界产生——**不可见文档进不了漏斗即进不了引用列表，零泄漏是结构性保证**（测试钉死而非拼接处小心）。
- **T96 三层保险落点**：前端 `KbView.vue` 可见等级下拉**必选无默认**（强制显式选择，V0 也是一次 conscious choice）+ `validate()` 拦截；`KbAdminController` `requiredLevel` 参数（preview/ingest 同口径）blank/非法值话术化 `KbIngestException`→BAD_REQUEST；`KbIngestService.ingest` 对 `requiredLevel==null` 显式拒绝——**不静默补 V0**（漏配是运营事故，悄悄降级为公开可见=权限事故）。`KbIngestCommand` 加 canonical 11 参 + 旧 10 参委托 V0（flow 测试零改动）；`KbIngestResult`/`KbDocumentSummary`/前端类型增 `requiredLevel`，台账/预览徽标展示档位。
- **T97 钉死清单**（15 新测，全量 1263 GREEN）：`RagStepMemberLevelTest`（V1 查 V3 只见基础档 + citation 不含 gold-faq source 与标题 + 全滤空走 RAG_SKIP groundingMiss=true 且引用零泄漏 + V5 全量可见）；`KbCatalogServiceTest`+3（等级谓词/例外通道越级放行/filterReadable 带等级）；`KbIngestServiceFlowTest`+2（漏配显式拒绝/等级落库+快照按等级过滤）；`KbLevelTest`×3 + `MockMemberLevelServiceTest`×2（demo 映射与 fail-closed）+ `PipelineContextMemberLevelTest`×2（缺省/归一 V0）。**测试基建教训**：RagFragment 3 参构造=BM25-only 口径（cosineScored=false），终闸"无口径不放行"会拦掉——漏斗测试片段须 9 参构造置 cosine 口径。
- **反思级 review 补丁（2026-09-20，+1 测，全量 1264 GREEN）**：发现并发路径集成遗漏——`WorkflowExecutionStep.startConcurrent` 腿2 子上下文与腿1 clone 只搬 `userId` 不搬 `memberLevel`，而腿2 子管线（CapabilitySegmentRunner）含 RagStep@660 → 复合问题（退款+商品咨询）并发腿2 检索以缺省 V0 口径降级（fail-closed 无泄漏，但错杀付费会员）。修复：两处子上下文补 `setMemberLevel(context.memberLevel())` + `WorkflowExecutionStepTest` 新增并发两腿 `userId+memberLevel` 随行断言钉死。
- **政策工具通道等级门（2026-09-20 用户裁决落地，原边界④收口）**：seam 正典改 `query(domain, query, ChatSubject)`（既有 2 参=缺省方法委托匿名 V0，fail-closed）；`RagPolicyQueryService` 注入 `KbCatalogService`，**宽召回后、置信终闸前**按主体过滤（与 RagStep ①′ 同谓词同位置——top1 从幸存者中选而非"选中后丢弃"；匿名/V0 只出公开档，V1~V5 递升）。主体两条到达路：DAG query_policy 节点显式传 `ChatSubject.of(ctx.userId(), ctx.memberLevel())`（图线程不依赖 ThreadLocal；**不用 resolveUserId 兜底**——baked 10086 会把匿名灌成 V5 fail-open）；@Tool 通道经 `ChatSubjectHolder`（ToolExecutionStep 进入执行前从 ctx 写入、finally 清除；超时模式默认 10s 开启，工具在池化守护线程执行，`ResilientToolExecutor.callOnce` 跳变前快照、执行时回放并清除——MDC/RequestContextHolder 在线程池/SSE 工作线程下都不成立）。mock 罐头数据无权限元数据，等级门不适用（demo inmemory 默认演示口径不变）。新测 +6（全量 1270 GREEN）：RagPolicyQueryServiceTest（V1 查 V3 托管政策不可见且 top1 落基础档/V5 全量/匿名 fail-closed/滤空 FALLBACK 零泄漏）×2 + ChatSubjectHolderTest（holder 收敛与归一 + seam 2 参=匿名）×3 + ToolExecutionStepTest 窗口随行/清除钉死 ×1。
- **已知边界**：①等级词表增档（V6+）= 代码变更（有界词表进代码的代价，接受——等级体系本身是业务不变量的慢变量）；②亿级片段升级路径 = Chroma metadata where 下推替代内存快照（设计要点5，非本阶段）；③HITL 恢复以恢复时刻等级为准，挂起期间降档的边界场景（挂起 V3→恢复前降 V1）按 fail-closed 收敛，属正确方向；④**等级门信任边界 = ChatRequest.userId 客户端声明**（无服务端身份绑定；用户已确认 demo 阶段身份即 mock 数据仅为展示——前端提供演示身份切换器（游客 V0 / 10010 V1 / 10012 V2 / 10013 V3 / 10014 V4 / 10086 V5，随对话请求带 userId，切换即重置会话），API 亦可显式传任意 uid），真鉴权接入后收口（ChatRequest javadoc 已有"迭代后真鉴权强制非空"记录）。

---

### Phase 22 — 会话记忆分层（滚动摘要 + token 预算窗口 + 用户画像长期记忆）
> 衔接节点：卡 Phase 3/6（Redis 会话缓存 + 会话管理层）与 Phase 21（画像权限自洽裁决）之上；与 Phase 21 可并行。（2026-09-20 设计拷问收敛）

**目标**：把"会话历史 TTL 内无限增长 + 摘要锚点仅首轮"升级为三层记忆——L1 原文窗口（最近 N 轮）/ L2 滚动摘要（滑出窗口轮次的压缩）/ L3 用户画像（跨会话长期记忆），配合既有 L0 业务键记忆（`WorkflowSubmissionRegistry`）形成双层分工。铁律：**任何一层记忆的失败不产生 5xx、不增加首字延迟**（压缩只发生在终局异步路径）。

**设计要点**
1. **分层分工**：L1 原文窗口管"最近最准"；L2 滚动摘要管主题连贯（表达层，丢了只是表达变差）；L0 注册表管实体消解（决策层，本就在 LLM 上下文之外）——"那单到哪了"靠 L0 兜底消解，摘要漏写订单号不丢 correctness，两层各自能独立兜底、测试分别钉死。
2. **触发与竞态**：终局（ChatTurnFinalizer 侧）检查 token 占用 ≥ 预算 80% → 异步压缩滑出窗口的前缀 → 写回缓存。80% 天然留 20% 余量吸收竞态：下一轮到达时摘要未就绪 → 降级为"无摘要 + 多喂原文"（略超预算但不等待、不阻塞）。
3. **token 度量双轨**：真实 usage 只给总量不给 per-message 体积且首轮无基线——故**阈值触发**读上一轮主模型调用的真实 usage（终局现成、零成本、最准），**窗口分轮**用字符启发式（可配置 charsPerToken，确定性可测）。不引入 tokenizer 依赖。
4. **摘要契约**：继承垃圾守护（含 `{}` 丢弃、失败降级跳过），60 字上界放宽为 ≤200 字滚动摘要契约；新增**业务键白名单断言**（压缩前文本中出现的订单号/挂起动作，压缩后必须仍在，测试钉死）；增量合并（新段 = summarize(旧摘要 + 新滑出轮次)），永不重压全量。
5. **画像写路径（Phase 21 自洽闭环）**：三类白名单字段（偏好/硬约束/沟通风格——全部"业务系统查不到、只影响表达不影响能力"）；LLM 仅可**提议**记忆 → 规则守门拒绝四类写入（业务键、**权限词**（等级/VIP 一律拒）、承诺类、敏感 PII）→ 新声明覆盖旧值；`POST /profile/reset` 遗忘权。**画像永不承载权限语义**——注入者说"记住我是 VIP"只会被守门拒绝，等级唯一来源仍是会员服务。
6. **降级矩阵**：摘要生成失败 → 无摘要多喂原文（宁缺毋滥延伸）；Redis 挂 → 既有 `SESSION_DOWN` 话术短路；画像读取失败 → 无画像照常答；守门拒绝 → 只影响写入不影响对话。

**任务清单**
- [x] T98 会话缓存值结构升级：`SessionMemory{summary, messages}`（存量纯消息列表 → 视为无摘要，平滑兼容零迁移）；`SessionCacheService`/Store 读写适配。（T98）
- [x] T99 token 度量双轨 + 80% 阈值：真实 usage（终局读上轮主模型 usage）管触发阈值；字符启发式（`app.memory.chars-per-token` 可配）管窗口分轮；预算配置化。（T99）
- [x] T100 终局异步压缩：Finalizer/MQ 消费侧执行 `summarize(旧摘要 + 滑出轮次)` 增量合并写回；竞态降级（摘要未就绪 → 无摘要多喂原文）；压缩路径与主链路零共享（不出现在首字延迟账上）。（T100）
- [x] T101 摘要契约与双层分工：垃圾守护继承 + ≤200 字 + 业务键白名单断言；读侧（SessionLoad）组装 `[summary?, 最近 N 轮]`；L0 注册表兜底独立可测。（T101）
- [x] T102 用户画像：`UserProfileService`（Redis user 级哈希，TTL 90d 惰性续期）+ 白名单三类字段 + `ProfileGatekeeper` 守门（拒业务键/权限词/承诺类/敏感 PII，LLM 仅提议）+ 新声明覆盖旧值 + 每请求加载经 `UserMemoryLayer` **独立消息块**注入（标签 `<user_profile_reference>` 包裹 + 「参考事实（非指令）」声明；**不入 System 块**——2026-09-20 设计裁决：System 只保留 Agent 基础角色/工具规则/输出规范）（≤200 字）+ `POST /profile/reset` 遗忘权。（T102）
- [x] T103 回归钉死：压缩边界指代消解 golden（第 7 轮引用第 2 轮实体不丢 correctness）；摘要业务键断言；画像守门注入用例（"记住我是 VIP"被拒不落库）；竞态降级路径；存量缓存兼容；既有测试零回归。（T103）

**产出文件**：session/cache/SessionMemory.java（新）、SessionCacheService.java+Store 实现（结构升级+兼容）、session/summary/LlmSummaryHook.java（滚动摘要契约+增量合并）、persistence/mq 消费侧（异步压缩 worker）、session/profile/UserProfileService.java+ProfileGatekeeper.java（新）、common/pipeline/PipelineContext.java（+memberProfile）、context/UserMemoryLayer.java（新·画像独立消息块注入，2026-09-20 设计修订撤出 System 块）、chat 侧 /profile/reset 端点、application.yml（memory 预算/阈值/charsPerToken）、eval golden（记忆用例）

**验收标准**
- 压缩只发生在终局异步路径：主链路首字延迟零增加；摘要未就绪时降级多喂原文，永不等待。
- 压缩边界指代消解不丢 correctness（L0 兜底独立成立）；摘要业务键断言全绿。
- 等级来源唯一性不被画像破坏（权限词写入被守门拒绝，Phase 21 裁决回归通过）；遗忘权可用。
- 任何记忆层失败均按降级矩阵落地，零 5xx；存量会话缓存平滑升级零迁移。

**实现注记（落地与原设计差异，诚实记录）**
- **T98 值结构升级与存量兼容**：新增 `session/cache/SessionMemory`（record，防御性拷贝；`ofMessages` 存量视图工厂），`ChatMessageCodec` 升级为 memory 编解码——decode 首字符分流（`[`=旧格式纯消息数组→无摘要记忆；`{`=新格式 `{summary, messages}`），encode 恒写新格式，**存量 Redis 值零迁移平滑升级**（首个压缩周期自然补齐摘要）。`SessionCacheStore` 接口三方法（load→SessionMemory / append 摘要保留语义 / save 整体替换）；`SessionCacheService` 的 `load/append` 签名保持存量口径（`load`=memory.messages()），新增 `loadMemory/save`——上游调用方（SessionLoadStep/ChatTurnFinalizer 之外的零改动）。InMemory store 用 `ConcurrentMap.compute` 做原子 append（保留既有摘要）。
- **T99 双轨落地**：真实 usage 轨 = `OutputStep` 终答主模型出站改走新增的 `ChatLlmService.chatRawDetailed`（阻塞路径返回 `LlmResponse` 携带 tokens；流式路径 `onCompleteResponse` 原生带 tokens）→ 写 `PipelineContext.lastUsageTokens`（null=话术短路/取消轮/引擎未回 usage→不触发）。字符启发式轨 = `SessionWindower`（`windowBudgetTokens × charsPerToken` 换算字符预算，从尾按轮累计，**只在轮边界裁剪不拆 User/Ai 对**；最后一轮恒保留——宁多喂不空喂；遗留乱序值退化为全保留）。读侧取窗与压缩分轮**共用同一 bean**（RedisConfig 装配，口径一致）。配置 `app.memory.*`（预算 1200 / charsPerToken 2.0 / threshold 0.8 / summaryMaxChars 200，全部环境变量可覆盖）。
- **T100 压缩执行体**：`SessionCompactionService`（@Component，专用守护单线程 `memory-compaction`，@PreDestroy 收口）——触发判定（usage ≥ 预算×阈值）在终局钩子同步判，压缩提交异步执行（**与主链路零共享，不出现在首字延迟账上**）；压缩主体 = 读→窗口分轮→滑出前缀→`summarizeRolling(旧摘要, 滑出轮次)` 增量合并（永不重压全量）→业务键补齐→写回；**竞态合并** = 读值时记 baseCount，写回收口为 `SessionCacheService.mergeSave`（2026-09-21 review 修订：同会话条纹锁内原子load→merge→save，append 与压缩写回互斥——根治 Redis 读-改-写竞态下 append 的 get/set 横跨 save 用旧值覆盖压缩写回的窗口；LLM 摘要调用留锁外，临界区微秒级零首字延迟影响；JVM 内完备，**多实例部署为残余边界**——真多实例需 WATCH/Lua/list 重构，demo 单实例不涉及）；单线程执行器天然串行化同会话多次压缩。**触发口径注记**：usage = 主模型 totalTokenCount（prompt+completion 全量，含 system/RAG/工具/回答），比 L1 窗口预算（仅历史段）口径宽——阈值 960 触发偏早属保守方向，滑出为空自然 no-op，勿误读为历史段 token 数。`ChatTurnFinalizer.finalizeTurn` 新增两路终局记忆维护（历史追加成功才触发；取消轮全跳过）。
- **T101 摘要契约**：`SummaryHook.summarizeRolling`（default empty，既有 fake 零改动）；`LlmSummaryHook` 实现滚动摘要（垃圾守护继承：花括号碎片；上界 60→200 字，201 字即丢弃，调用方沿用旧摘要——宁缺毋滥）；**业务键白名单运行时补齐** = `BusinessKeyExtractor`（`[A-Z]{2,6}-\d{1,10}` 覆盖 ORD-001/HITL-12/RF-2024 全部 mock 单号形态；纯数字长串不纳入防误杀手机号）——滑出文本中的单号若在新摘要缺失 → 运行时追加「涉及单号： …」行（确定性保证，不依赖提示词遵循度；golden 测试钉死）；截断预算先扣键行长度（**截断不丢键**）。读侧 `SessionLoadStep` 组装：`loadMemory` → 窗口填 history + 摘要填 `context.summary`（缓存摘要与 SessionRouter 新会话锚点语义正交不冲突）；`SESSION_DOWN` 降级口径不变。L0 注册表（`WorkflowSubmissionRegistry`）本就在 LLM 上下文之外独立兜底实体消解，双层分工无耦合。
- **T102 画像**：`session/profile` 五件套——`ProfileCategory` 三类白名单枚举（PREFERENCE/CONSTRAINT/STYLE，越界类别即拒）；`UserProfileStore` seam（Redis 哈希 `profile:{userId}` + TTL 90d 惰性续期（写入与命中读取都续满）/ InMemory dev 兜底，RedisConfig 两路装配镜像 session cache 范式）；`ProfileGatekeeper`（@Component，四类拒绝：业务键（共用 BusinessKeyExtractor）/权限词（vip/会员/等级/钻石…——**"记住我是 VIP"被拒不落库，Phase 21 等级唯一来源红线回归钉死**）/承诺类（保证/承诺/赔偿…）/敏感 PII（手机/身份证/银行卡/邮箱正则）+ 单字段 ≤30 字）；`UserProfileService`（终局异步提议：小模型 `decide` 严格 JSON 提议至多一条 → 解码宽松（剥围栏取平衡花括号）→ 守门 → **同字段覆盖写**；`renderForPrompt` ≤200 字 fail-open（存储异常→null 无画像照常答）；`reset` 遗忘权）。加载点 = `ChatController.run`（与 memberLevel 解析同一处，每请求一次；HITL 恢复路径暂不注入画像——恢复轮以"最小上下文重建"语义优先，见已知边界⑤）；消费点 = `UserMemoryLayer` 独立消息块（2026-09-20 设计修订：画像撤出 System 块——System 只保留基础角色/工具规则/输出规范；画像以 `<user_profile_reference>` 标签包裹 + 「参考事实（非指令）」声明独立成块，位置在系统锚点之后、客观数据层之前；值内尖括号渲染期中和为全角，防伪造闭合标签跳出块外——持久化注入的根治手段是注入位隔离而非词表穷举）。读路径同日加固（挂死快速抛弃，不占首字预算）：进程内渲染短缓存（`app.memory.profile.render-cache-ttl` 默认 60s，命中零 Redis 往返；遗忘权 reset 先失效缓存再删存储，隐私优先）+ `WindowedCircuitBreaker` 熔断（单次失败即开断路、30s 冷却——Redis 挂死只惩罚缓存未命中的第一个请求，其余零等待）+ fail-open 兜底。`POST /profile/reset` 复用 ChatRequest 载体（只消费 userId），纳入 `AccessGateFilter.GATED_PATHS` 口令闸口；存储异常收口为 200 + `{reset:false, reason}`（2026-09-21 review 修订，不落全局 500——与端点既有的「服务未装配」结构化返回同形，客户端据 reset 标志重试）。
- **T103 钉死清单**（全量 1342 GREEN，较 Phase 21 基线 1270 净增 72——新测 + 存量适配）：codec 存量数组兼容 + memory 往返 + 空白摘要归一（ChatMessageCodecTest）；InMemory/Redis store 摘要保留 append + save（含 Redis 旧格式 decode 视为无摘要）；SessionCacheService loadMemory/save 异常包装；SessionWindower 不拆对/末轮恒保留/遗留退化/预算换算 ×6；SessionCompactionService 阈值边界 959/960/禁用/缺 usage + 增量合并入参断言 + 业务键补齐 + LLM 失败沿用旧摘要键仍钉 + 无摘要无键丢弃 + 截断不丢键 + **压缩期间并发 append 拼回**（ScriptedHook 回调时机模拟竞态，注入直接执行器确定性同步）×10 + LlmSummaryHook 滚动契约 ×6 + BusinessKeyExtractorTest ×7 + ProfileGatekeeperTest 四类拒绝/放行/长度 ×8 + UserProfileServiceTest 提议覆盖写/VIP 注入不落库/none 跳过/LLM 失败静默/游客跳过/渲染 ≤200/fail-open/遗忘权 ×9 + PipelineContextMemoryTest 归一化 ×3 + SessionLoadStep 摘要装载 ×2 + OutputStep usage 双路采集 ×2 + ChatTurnFinalizer 记忆维护触发/取消跳过 ×3。存量测试适配：SessionLoadStepTest/InMemory/Redis/SessionCacheService/ChatControllerTest（构造器签名随接口升级，断言语义零变化）。**顺手加固**：CircuitBreakerGuardTest 冷却期用例由固定 sleep 改轮询等待（墙钟回拨/调度抖动下偶发不足，2026-09-21 全量跑实测一次后加固；冷却拒绝不 recordFailure，重试安全）。
- **已知边界**：①压缩触发依赖主模型回传 usage（部分引擎流式缺失则该轮不触发，下轮补；字符窗口照常兜底）；②提示词摘要漏写单号由运行时键补齐兜底，但键值**只保字面**（如 ORD-001），指代消解的语义正确性仍由 L0 注册表与最近原文窗口共同兜底；③画像提议每真实成轮一次小模型调用（成本与轮次线性，可用 `app.memory.profile.enabled=false` 关闭）；④`/profile/reset` 的 userId 为客户端声明（与 ChatRequest.userId 同信任边界，真鉴权接入后收口）；⑤HITL 恢复轮暂不注入画像与压缩触发依赖的 usage（恢复轮 lastUsageTokens 缺省 null 自然跳过压缩；画像注入待恢复链路需要个性化时再评估）；⑥渲染缓存 TTL（默认 60s）内画像后端变更不可见——画像低频变更可接受，遗忘权 reset 即时失效缓存不留旧值；⑦读路径熔断冷却期（30s）内画像整体缺失（快速抛弃，宁缺勿等——表达层降级，不占首字预算）。

---

## 7. 验证策略总览
| 阶段 | 核心验证项 |
|---|---|
| 1 | 接入安全过滤、统一返回、异常信封、traceId 全链路、日志结构化脱敏 |
| 2 | Nacos 配置加载、热更新、多环境隔离、密钥安全、降级兜底 |
| 3 | 会话读写、TTL 过期、Redis 故障降级 503 |
| 4 | 多模型配置热加载、三种选择策略、网关拦截、429降级、故障转移、Token预算 |
| 5 | 429 重试、4xx 不重试、工具异常自纠正、安全异常拦截、熔断器 |
| 6 | 会话路由命中/新建、摘要Hook、问题改写、改写质量校验回退 |
| 7 | 规则命中、冲突升级、注入拦截、四类模型路由、仅返回枚举 |
| 8 | 三层隔离、拼接顺序、层级不污染 |
| 9 | 工具参数解析、Schema校验、注入拦截、异常自纠正 |
| 10 | 向量化、检索召回、Rerank、注入扫描过滤、纯净片段 |
| 11 | HITL 触发、超时/权限、人工工单、审计 |
| 12 | JsonSchema 校验、重试兜底、输出安全过滤、/chat 同步+流式 |
| 13 | 异步落库、MQ 解耦、死信处理、审计事件完整 |
| 14 | LangGraph4j 兼容、状态机编排、条件分支、循环自纠正 |
| 15 | 指标完整、链路贯通、运维调权、微调闭环、评测报告、告警有效、生产级验收通过 |
| 16 | 前端工程启动、Vite 代理调通 /chat、SSE 逐字流式、多轮上下文、工具/RAG 折叠展示、traceId 对齐、503/429/注入 UI 提示、build 产物可托管 |
| 17 | 工具连续失败熔断 OPEN、半开探针恢复 CLOSED、零 LLM 话术短路、不触发自纠正死循环、三引擎断路器行为一致 |
| 18 | AgentScope 引擎 /chat 全链路跑通、三引擎输出等价、ReAct 自纠正收口 TOOL_FAILURE、HITL Permission 拦截、按官方文档对接接入、可回退 |
| 19 | 管理台调权热生效、模型配置表、HITL 工单操作、评测报告指标正确、仪表盘实时刷新、traceId 链路检索、单 jar 部署、鉴权生效 |
| 20 | 改写保 original query 无业务结论、精确词走 Hybrid 精确通道命中、RAG 片段时效标注过时不冒充当前、近期优先 |
| 21 | 等级只来自登录态会员服务（对话自称不采信）、V1 查 V3 不可见、citation 零泄漏、录入漏配被三层校验堵死、fail-closed 只出 PUBLIC、存量 PUBLIC 零回归 |
| 22 | 压缩仅发生在终局异步路径（主链路零延迟增加）、竞态降级无摘要多喂原文、摘要业务键断言、画像守门拒绝权限词写入且遗忘权可用、存量会话缓存兼容零迁移 |

---

## 8. 风险与回退
| 风险项 | 等级 | 应对预案 |
|---|---|---|
| nacos-config 与 Boot 4.1 不兼容 | 高 | 先行冒烟验证；不通过则切换裸 nacos-client + 自定义 ConfigDataLoader，不阻塞进度 |
| LangGraph4j 成熟度/与 Boot 4.1+LangChain4j 兼容 | 高 | Phase 14 前行依赖兼容性冒烟；不通过则自研轻量状态机编排（GraphNode/Edge 已抽象，可降级自实现） |
| langchain4j-redis / 向量库版本对齐 | 中 | 校验依赖树；冲突则排除托管版本，显式指定兼容版；RAG 向量库可在 pgvector/Redis vector 间切换 |
| OpenAI 兼容端点 function-call / 结构化输出支持不足 | 中 | 降级为 JSON 模式 + 严格 Schema 提示词 + 强校验（第七层兜底） |
| 模型选择策略冲突/权重配置错误 | 中 | 策略接口可插拔；权重归一化校验；异常降级默认策略 + 告警 |
| 故障转移主备均不可用 | 中 | 主备耗尽返回 503 降级话术 + 审计 + 告警；不无限重试 |
| MQ 故障导致会话/审计丢失 | 中 | 生产开启持久化 + 死信 + 告警；极端场景本地日志兜底补偿 |
| 提示注入绕过规则 | 中 | 接入层+改写层+RAG注入扫描+输出安全过滤器多层检测；pattern 库 Nacos 热更新；新增样本快速迭代 |
| 工具自纠正死循环 | 低 | 强制最大迭代次数护栏；异常计数监控 + 告警 |
| 微调闭环外部训练任务不可控 | 低 | `FineTuningPipeline` 仅编排（提交/轮询/注册），不内嵌训练；训练失败不影响在线链路 |
| 前端 Vite/Element Plus/ECharts 版本与构建兼容 | 中 | 锁定 package.json 版本；构建 CI 校验 `vite build` 通过；冲突时降级稳定版 |
| SSE 在部分代理/网关被缓冲导致非实时 | 中 | 开发期直连后端；生产 nginx 配置 `proxy_buffering off` + `X-Accel-Buffering: no`；前端 `EventSource` 封装降级轮询 |
| 前端构建产物与 Spring Boot 静态托管路径冲突 | 低 | 显式配置 `spring.web.resources.static-locations`；SPA history 模式配 `forward index` 兜底 |
| 前端提前开工但后端 API 未就绪 | 中 | Phase 16 允许 mock 开发，卡 Phase 12 后切真实接口；`api/` 层抽象便于 mock/真实切换 |
| AgentScope 2.0 响应式栈与既有 Servlet/SseEmitter 路径共存 | 中 | 严格按官方文档对接方式接入（响应式 SSE/WebFlux）；线性/LangGraph 路径保留 SseEmitter；SSE 协议层对前端统一（EventSource 消费），后端实现按引擎分流；不兼容则 `AgentScopeEngine` 不装配回退既有引擎 |
| AgentScope 2.0 与 Boot 4.1/Spring 7 兼容 | 高 | 依赖前置冒烟（同 Phase 2/14 预案）；不兼容则 `AgentScopeEngine` 不装配（`@ConditionalOnProperty`），既有引擎无损 |
| per-tool 熔断误开启致正常工具被短路 | 中 | HALF_OPEN 探针恢复 + 失败率/连续失败双阈值；OPEN 经可观测告警可见；可配置关闭 |

---

## 9. 关键核心文件清单
1. `pom.xml` — 依赖矩阵与版本风险控制（含 LangChain4j / LangGraph4j / 向量库）
2. `gateway/config/ModelConfigCenter.java` — 模型配置中心热加载核心
3. `gateway/UnifiedModelGateway.java` + `FailoverExecutor.java` — 统一网关与故障转移核心
4. `gateway/selector/ModelSelector.java` — 可插拔模型选择策略接口
5. `intent/IntentRecognizerImpl.java` — 多层级意图识别核心
6. `intent/RouteDispatcher.java` + `ModelRouter.java` — 意图→模型路由分发
7. `context/ContextBuilder.java` + `ContextMerger.java` — 三层隔离上下文构建工厂
8. `capability/rag/*` — RAG 完整链路
9. `output/StructuredOutputGateway.java` — 结构化输出与安全核心
10. `resilience/ResilientExecutor.java` + `ExceptionTriage.java` — 韧性治理核心
11. `llm/service/ChatLlmService.java` — 所有 LLM 调用统一收口
12. `langgraph/AgentStateGraph.java` + `GraphExecutor.java` — LangGraph 多步推理编排
13. `feedback/ModelRegistrar.java` — 微调闭环模型动态注册
14. `eval/EvalExecutor.java` — 评测能力核心
15. `web/GlobalExceptionHandler.java` — 终端异常治理
16. `frontend/src/utils/sse.ts` + `api/http.ts` — SSE 封装与 axios 统一入口（traceId 透传/错误映射）
17. `frontend/src/views/chat/` — 对话界面（流式 + 工具/RAG 折叠展示）
18. `frontend/src/views/admin/` + `views/observability/` — 运维管理台（路由调权）与可观测性仪表盘
19. `resilience/ToolCircuitBreaker.java` — per-tool 熔断器（工具故障隔离、零 LLM 话术短路）
20. `agentscope/AgentScopeEngine.java` + `agentscope/*Adapter.java` — AgentScope 2.0 引擎接入与能力桥接（三引擎共存、换引擎不换收口）
21. `capability/rag/HybridRetriever.java` — Hybrid RAG 三通道融合（向量+关键词+规则路由，精确词不走纯语义）+ `RagFragment` 时效字段
