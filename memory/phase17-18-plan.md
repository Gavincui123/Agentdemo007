---
name: phase17-18-plan
description: Phase 17 工具断路器已实现(T73-T77 GREEN,555测)；Phase 18 AgentScope 2.0 集成仍为计划(未实现,coexist-as-engine via PipelineExecutor seam)；前端管理台由 17 重编号为 19
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-07T15:05:43.164Z
---

Phase 17/18 已写入 `docs/DEVELOPMENT-PLAN.md`。**Phase 17 已 TDD 落地（T73-T77 全 GREEN，全量 555 测零回归，10 新测）；Phase 18 仍为计划（未实现，独立章节，按用户意 deferred）**。

**Phase 17 — 工具调用断路器（T73-T77，已实现）：** per-tool 三态熔断（复用 Phase 5 `CircuitBreaker`，`ToolCircuitBreaker` 内部 `ConcurrentHashMap<String,CircuitBreaker>` 按 toolName 惰性建实例）。OPEN→抛 `ToolCircuitOpenException`（**非** `ToolRecoverableException` 子类，确保既有 catch 不吞）→`ToolExecutionStep` 收口 `ShortCircuit(TOOL_FAILURE)`（HTTP 200，零 LLM，①②④交汇）。

**实现与原设计差异（关键）：**
- **T73 时钟**：实际用 `LongSupplier`（epochMillis，复用 Phase 5 CircuitBreaker），非原设计 `Supplier<OffsetDateTime>`。
- **T74 记账位置**：在 `ToolExecutor.execute()` 公共边界记 success/failure（非递归 `executeCall` 内），dev 路径干净。
- **T75 复用 `TOOL_FAILURE` 而非新增 `TOOL_CIRCUIT_OPEN`**：避免改 `DegradationPhraseCenter`/`AuditEventType` 映射面；三重隔离区分熔断 vs 耗尽——①异常类型②日志（"熔断中"vs"自纠正耗尽"）③指标（`agent.tool.circuit.open` 独立计数）。话术一致（皆"稍后重试"）。
- **T76 per-case 不可表达（限制）**：熔断 OPEN 是**跨请求累积态**，单 eval 用例（一请求一断言）内无法触达阈值→OPEN。故不扩 `eval/tool.json`，改由 3 单测覆盖：`ToolExecutorCircuitTest`（接入三态）、`ToolExecutionStepCircuitTest`（收口）、`ToolCircuitWiringTest`（`@SpringBootTest`+`@DirtiesContext` 注册 flaky 工具连调阈值次→第三次抛 `ToolCircuitOpenException`，锁定装配）。
- **T77**：`AgentMetrics.recordToolCircuitOpen(toolName)`→counter `agent.tool.circuit.open`(tool 标签)，独立于 `recordTool(success)`（熔断是跨请求累积态不混计）。`ToolConfig` 新增 `@Bean ToolCircuitBreaker`（`app.tool.circuit.failure-threshold`/`cooldown-ms` 缺省 3/30000，`System::currentTimeMillis`），`toolExecutor` @Bean 改 6 参构造。P1 告警规则 `tool.circuit.open.count>0` 入 `AlertConfig.standardRules`（P1 非 P0：经 ②每步降级平台仍可用）。

**产出**：resilience/ToolCircuitBreaker.java、ToolCircuitOpenException.java；capability/tool/ToolExecutor.java(6参)、ToolExecutionStep.java(收口+埋点)、ToolConfig.java(@Bean)；observability/AgentMetrics.java、alert/AlertConfig.java。

**Phase 18 — AgentScope 2.0 集成重构 Agent 构建架构（T78-T87，未实现，独立章节 deferred）：** 引入 AgentScope Java 2.0（`io.agentscope:agentscope-core`/`-harness`/`-extensions-model-openai`，Maven Central，JDK 17+，基于 Project Reactor，v2.0.0 GA 2026-07 Alibaba Tongyi Lab 开源，阿里 13+ BU 生产验证）作第三执行引擎。
- **迁移策略（关键决策）**：coexist-as-engine + 渐进迁移，**非**全量替换。`AgentScopeEngine implements PipelineExecutor`（`@ConditionalOnProperty app.pipeline.mode=agentscope`）与线性 `PipelineOrchestrator`/LangGraph `GraphExecutor` 三引擎互斥可切可回退（沿用 Phase 14 切换模式）。换引擎不换收口（§5.14，PipelineContext/StepOutcome 为引擎边界）。
- **能力映射**（经 seam 桥接不丢四原则）：PipelineContext↔RuntimeContext/AgentStateStore；ToolExecutor/Reparser↔Toolkit（Reparser seam 作 reparse 通道，Phase 17 断路器随 Toolkit 迁移）；ModelExecutor↔Model（保留 ResilientExecutor 包裹，韧性层统一不双轨）；HitlHandler↔Permission System（allow/require-approval/deny，HitlStep 收口 ShortCircuit(HITL_TIMEOUT) 不变）；SessionCacheService↔分布式 AgentStateStore(Redis)；AgentMetrics/AuditEvent↔Agent Evolution。
- **接入方式（用户确认修正·item 4）**：AgentScope 2.0 接入**严格按官方文档对接方式**（java.agentscope.io Quickstart/Harness Architecture/Spring 集成章节），不另造桥接。本项目**不坚持** Servlet 栈+SseEmitter / **不拒绝 WebFlux**——AgentScope 基于 Reactor(Mono/Flux)，按官方方式接入响应式 SSE/WebFlux；线性/LangGraph 引擎路径保留既有 SseEmitter（§2 决策修正：引擎分流，AgentScope 路径用官方响应式栈，不强制 Servlet 桥接）。SSE 协议层对前端统一(EventSource 消费)，后端实现按引擎分流，前端(Phase 16)不耦合后端引擎。
- **HarnessAgent 全量工程能力**（workspace/sandbox/sub-agents/Plan Mode/skills 四层）按需渐进开启，非一次到位；分布式部署(K8s/AgentRun cloud sandbox)属部署门禁。

**重编号（易忘点）：** 原 Phase 17 前端管理台→**Phase 19**（为 17/18 让位）。Phase 16 前端对话界面不变。dev-plan §0 总览表/§0 开发顺序说明/§6 顺序说明/§7 验证表/§8 风险表/§9 关键文件清单已全量同步。

**AgentScope 2.0 研究来源**（本会话 tavily 检索）：https://java.agentscope.io/v2/en/intro.html 、 https://github.com/agentscope-ai/agentscope-java 、 Harness 架构 https://java.agentscope.io/v2/en/docs/harness/architecture.html 。

**Phase 18 — AgentScope 2.0 集成重构 Agent 构建架构（T78-T87）：** 引入 AgentScope Java 2.0（`io.agentscope:agentscope-core`/`-harness`/`-extensions-model-openai`，Maven Central，JDK 17+，基于 Project Reactor，v2.0.0 GA 2026-07 Alibaba Tongyi Lab 开源，阿里 13+ BU 生产验证）作第三执行引擎。
- **迁移策略（关键决策）**：coexist-as-engine + 渐进迁移，**非**全量替换。`AgentScopeEngine implements PipelineExecutor`（`@ConditionalOnProperty app.pipeline.mode=agentscope`）与线性 `PipelineOrchestrator`/LangGraph `GraphExecutor` 三引擎互斥可切可回退（沿用 Phase 14 切换模式）。换引擎不换收口（§5.14，PipelineContext/StepOutcome 为引擎边界）。
- **能力映射**（经 seam 桥接不丢四原则）：PipelineContext↔RuntimeContext/AgentStateStore；ToolExecutor/Reparser↔Toolkit（Reparser seam 作 reparse 通道，Phase 17 断路器随 Toolkit 迁移）；ModelExecutor↔Model（保留 ResilientExecutor 包裹，韧性层统一不双轨）；HitlHandler↔Permission System（allow/require-approval/deny，HitlStep 收口 ShortCircuit(HITL_TIMEOUT) 不变）；SessionCacheService↔分布式 AgentStateStore(Redis)；AgentMetrics/AuditEvent↔Agent Evolution。
- **接入方式（用户确认修正·item 4）**：AgentScope 2.0 接入**严格按官方文档对接方式**（java.agentscope.io Quickstart/Harness Architecture/Spring 集成章节），不另造桥接。本项目**不坚持** Servlet 栈+SseEmitter / **不拒绝 WebFlux**——AgentScope 基于 Reactor(Mono/Flux)，按官方方式接入响应式 SSE/WebFlux；线性/LangGraph 引擎路径保留既有 SseEmitter（§2 决策修正：引擎分流，AgentScope 路径用官方响应式栈，不强制 Servlet 桥接）。SSE 协议层对前端统一(EventSource 消费)，后端实现按引擎分流，前端(Phase 16)不耦合后端引擎。
- **HarnessAgent 全量工程能力**（workspace/sandbox/sub-agents/Plan Mode/skills 四层）按需渐进开启，非一次到位；分布式部署(K8s/AgentRun cloud sandbox)属部署门禁。

**重编号（易忘点）：** 原 Phase 17 前端管理台→**Phase 19**（为 17/18 让位）。Phase 16 前端对话界面不变。dev-plan §0 总览表/§0 开发顺序说明/§6 顺序说明/§7 验证表/§8 风险表/§9 关键文件清单已全量同步。

**AgentScope 2.0 研究来源**（本会话 tavily 检索）：https://java.agentscope.io/v2/en/intro.html 、 https://github.com/agentscope-ai/agentscope-java 、 Harness 架构 https://java.agentscope.io/v2/en/docs/harness/architecture.html 。

相关：[[phase14-langgraph-design]]、[[phase15-t72-closeout-design]]、[[phase20-retrieval-enhancement-plan]]、[[degradation-and-eval-principles]]。
