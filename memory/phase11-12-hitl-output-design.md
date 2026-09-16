---
name: phase11-12-hitl-output-design
description: Phase 11 HITL + Phase 12 结构化输出/ChatController 设计决策（HITL 短路 vs 输出降级映射/dev 启动链/SSE 手写 String 而非 SseEmitter/chatRaw 不二次包裹/@Order 610/800）
metadata:
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-06T07:53:45.973Z
---

Phase 11（HITL）+ Phase 12（结构化输出与安全网关 + ChatController）已实现，TDD 全绿（截至 2026-09-06 全量 **408** 测试，较 Phase 9/10 的 342 +66）。DEVELOPMENT-PLAN.md Phase 11/12 任务清单已勾 [x]。

**非显而易见的设计决策（未来会话需知）：**

1. **§5.12 降级行映射是关键契约：HITL 阻塞短路，结构化输出不阻塞降级，模型故障短路。** HITL 行「话术(HITL_TIMEOUT)+工单」→ `ShortCircuit(HITL_TIMEOUT)`（**阻塞**，跳过后续零 LLM；建 `HumanTicket` PENDING，超时→TIMEOUT、权限不足→挂起，工单不阻塞主链路但不放行高风险动作）。结构化输出行「兜底默认结构+话术兜底」→ `Degrade(OUTPUT_FALLBACK)`（**不阻塞**，finalReply 置为话术后继续推进产出回复）。模型行→ `ShortCircuit(MODEL_DOWN)`（无可用模型 `ModelSelectionException`）/ `ShortCircuit(FAILOVER_EXHAUSTED)`（容灾耗尽 `LlmUnavailableException`）。三者经 [[degradation-and-eval-principles]] 的 StepOutcome 出口统一收口。

2. **OutputStep(@Order 800) 是终端步骤，串完整输出链。** 流程：flatten `assembledPrompt`(List<ChatMessage>→按 `\n` join content)→`ChatLlmService.chatRaw`(**不二次包裹**：组装 prompt 已由 ContextBuilder 三层隔离+定界符包裹，二次 sanitize 会把 System 锚点当用户数据包破坏层级)→`setModelResponse`→`StructuredOutputGateway`(校验+重试兜底)→`OutputSecurityFilter`(脱敏/注入残留替换)→`setFinalReply`。schema 耗尽→`Degrade(OUTPUT_FALLBACK)`；`ModelSelectionException`→`ShortCircuit(MODEL_DOWN)`；`LlmUnavailableException`→`ShortCircuit(FAILOVER_EXHAUSTED)`。**chat(prompt,intent) sanitize 包裹 vs chatRaw(prompt,intent) 直传**——OutputStep 主链路用 chatRaw，辅助 LLM（摘要/改写/分类）用 chat。prod 应改 chatRaw 接收 `List<ChatMessage>` 结构化消息（延后薄层）。

3. **ChatController 终端翻译：所有 PipelineResult 都 HTTP 200 + code=0（话术短路不暴露技术码）。** `PipelineResult`(reply/degraded/scenario) → `UnifiedResponse.success(ChatResponse{sessionId,reply,degraded,scenario})`。正常/降级/短路**统一 success**，降级信息仅经 `degraded`/`scenario` 元字段透出供客户端可选展示（与接入层 `InputSecurityFilter`/`ValidationFilter` 短路数据形状 `{reply,degraded,scenario}` 一致——终端与边界同形，第四原则）。sessionId 缺省/空白→UUID 生成并回传（多轮续）。

4. **SSE `/chat/stream` 用手写 `text/event-stream` String，而非 `SseEmitter`。** body=`"data:"+objectMapper.writeValueAsString(ChatResponse)+"\n\n"`，`Content-Type: text/event-stream`。理由：①真实 token 流式延后（Phase 12 单事件里程碑），SseEmitter 的异步容器耦合对单事件无收益；②手写 String 可纯单测（`ResponseEntity<String>` 直接断言 body，无需 MockMvc asyncDispatch/Spring 容器）。**这是有意决策，未来接真实流式再切 SseEmitter，出口形状（单事件 ChatResponse JSON）不变。**

5. **全链路 dev 可跑通的前提：`ModelConfigBootstrap`(CommandLineRunner) 启动即 `center.refresh()` + `GatewayConfig.devSnapshot` 单 `dev-noop` 启用模型种子 → `NoopModelExecutor` 占位回复。没有 bootstrap 则注册表空 → `ChatLlmService.resolvePrimary` 抛 `ModelSelectionException` → OutputStep 收口 `MODEL_DOWN`（/chat 不可用）。Phase 13/14/16 接入需知此依赖链。null 流控/容灾安全（`TokenBudgetChecker.check` 空策略直通、`ChatLlmService` failover 缺省自建）。

6. **@Order 槽位更新：HitlStep 610（夹 RouteDispatch 600 与 Tool 650 之间，转人工零 LLM 短路工具/RAG/上下文/LLM）/ OutputStep 800（紧随 ContextBuilder 700）。** `DegradationScenario` 新增 `OUTPUT_FALLBACK`（HITL_TIMEOUT 与 INTERNAL 之间）。

7. **Java 17：sealed 类的 pattern-switch 是 preview 且禁用，必须用 `if (x instanceof Sub c)` 链**（HitlStep、OutputStep、PipelineOrchestrator 均如此；详见 [[spring-boot-4-jackson3-gotchas]] 同源坑）。`ValidationResult` record 组件 `valid` 会生成同名 accessor `valid()`，静态工厂 `valid()` 编译失败→改名 `ok()`/`fail()`。

**eval 数据：** `eval/hitl.json`（hitl-001..005：转人工/投诉短路+工单、非高风险 Proceed、超时 TIMEOUT、权限不足 PENDING）、`eval/output.json`（output-001..005：正常透传、Schema 耗尽 OUTPUT_FALLBACK、MODEL_DOWN、手机号脱敏、注入残留安全话术替换）、`degradation.json` 新增 deg-010（OUTPUT_FALLBACK, DEGRADE）。

**延后项：** 真实 token SSE 流式（切 SseEmitter）、LangChain4j ChatModel 桥接（dev 用 NoopModelExecutor）、`chatRaw` 改收 `List<ChatMessage>`、`ReAsk`/`OutputSchemaResolver`/`DecisionResolver`/`PermissionChecker` 的 prod 覆盖（dev 落 none()/lenient()/none()/alwaysPermitted()）、请求体注入扫描随 /chat 落地补齐（当前 InputSecurityFilter 只扫 URI+参数，body 注入由流水线兜底）。基线已落 application.yml：`app.hitl.timeout-ms`、`app.output.max-retries`。详见 docs/DEVELOPMENT-PLAN.md Phase 11/12。衔接：Phase 16 前端对话界面卡本 Phase `/chat` 打通。
