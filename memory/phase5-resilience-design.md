---
name: phase5-resilience-design
description: Phase 5 韧性层设计——重试/故障转移分层、异常传播语义、熔断与工具反馈，6 验收全满足（130 测试）
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-04T03:24:47.163Z
---

Phase 5 韧性层（异常分诊 + 重试退避 + 熔断 + 工具反馈）已落地，33 个新测试（套件 97→130 全绿），6 条验收功能性全满足。

**分层（核心决策，呼应 [[phase4-gateway-design]]）：** ResilientExecutor 包裹"单模型"执行，置于 FailoverExecutor 内部——重试=同模型退避（指数+全抖动，遵循 Retry-After 不抖动），重试耗尽=切下一个候选模型，故障转移=换模型，三者正交。FailoverExecutor 逐候选各试一次（`min(maxAttempts, candidates.size())`，不再循环重复同模型——修了原 `get(attempt % size)` 的冗余重试缺陷）。

**异常传播语义（§5.8 + NonRetryableException javadoc 推导，测试固化）：**
- RETRYABLE_TRANSIENT（瞬态 429/5xx/超时）→ 同模型退避重试，耗尽切备选。
- NON_RETRYABLE_CLIENT（401/403/404/预算超限）→ 不重试同模型，但仍切备选（不同凭证/端点）。关键区分：提供方 429（TransientException 带 retryAfterMs，可重试）vs 我方预算超限（RateLimitExceededException，不重试，需窗口重置）。
- TOOL_RECOVERABLE → 不故障转移，向上传播交 ToolErrorFeedback 反馈 LLM 自纠正。
- FATAL（注入/权限/非法）→ 不故障转移、不提交 LLM，向上传播交上层审计拦截。
FailoverExecutor 用 `ExceptionTriage.triage(e).decision()` 判定：FEEDBACK_TO_LLM / AUDIT_AND_FAIL → 传播；RETRY / FAIL → 切备选。

**组件清单（src/main/java/com/agentdemo007/resilience/）：**
ExceptionTriage 四层分诊（7 测试）、BackoffStrategy 指数退避（3）、RetryPolicy、ResilientExecutor 重试模板（6）、CircuitBreaker 三态熔断 CLOSED/OPEN/HALF_OPEN 时钟可注入（7）、Transient/NonRetryable/ToolRecoverable/FatalException、Decision、TriageResult、Sleeper/RetryAction 缝、ToolErrorFeedback+ToolFeedbackOutcome sealed（3）。FailoverExecutor 重构：3 参构造 `(ResilientExecutor, RetryPolicy, ExceptionTriage)` + 无参委托 noRetry 兼容既有调用（7 测试 + 既有零回归）。

**GatewayConfig 装配（prod 启用重试-故障转移）：** BackoffStrategy / ExceptionTriage / RetryPolicy.defaults() / ResilientExecutor(prod Sleeper=Thread.sleep + new Random) / FailoverExecutor(3 参) 全 @Bean，contextLoads 校验通过。ToolErrorFeedback 的 @Bean 留待 Agent 编排层消费时再装（无消费者，YAGNI）。

**DEFERRED：** CircuitBreaker per-model 故障转移接线（单一全局熔断会阻断备选切换，需 per-model 注册表）；CompositeLlmCallbackHandler 流式异常分诊（随 LangChain4j）；ToolErrorFeedback 自纠正循环本体（重提交→LLM→修正调用→成功，属 Agent 编排层）；Nacos 提示词托管 ToolErrorFeedback 反馈模板（见 [[nacos-prompt-registry-fit]]）。

**测试缝：** Sleeper（不真实睡眠/记录延迟）、LongSupplier 时钟、Random 确定性抖动、ScriptedExecutor（按序返回响应/抛异常 + 记录 modelId）。TDD 纪律：先写测试→观察 RED→最小实现→GREEN，全程 `./mvnw test` 验证，不自报 GREEN。
