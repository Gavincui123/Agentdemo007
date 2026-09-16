---
name: q2-token-streaming
description: "Q2 真 token 流式已实现(891测,1 smoke skipped)——引擎无关 StreamingReplyHandler seam→ModelExecutor.stream→RoutingModelExecutor→UnifiedModelGateway→ChatLlmService.chatRawStream(主模型 only 无中途故障转移)→OutputStep 流式分支(TokenChunk→reply_chunk SSE,onError→阻塞 chatRaw fallback)→LangChain4jModelExecutor.stream leaf(OpenAiStreamingChatModel)；流式跳 Schema/无 toolSpec；SSE-fake 单测延后"
metadata:
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-14T02:57:41.085Z
---

Q2「前端无流式 + 延迟 37s」实测修复——真 token 流式链**已实现**（2026-09-14，全量 897 GREEN / 9 skipped；新增 `LangChain4jModelExecutorStreamingSiliconFlowSmokeTest` env-gated SF_KEY 无→skipped）。根因 + Q2 埋点详见 [[business-tools-workflow-dag]] 实测发现段（OutputStep.chatRaw 阻塞→无 token 级流式，用户见"转圈 37s→整段弹出"）。本记忆只记流式链设计。

**⚠️⚠️ 生产链第二处断裂修正（2026-09-14，用户实测报"流式生成失败，回退阻塞 chatRaw reason=...ClassCastException"）**：第一处（CircuitBreaking 缺 stream 覆写，UOE）修后，**leaf `LangChain4jModelExecutor.stream` 还有 ClassCast**——`OpenAiStreamingChatModel.doChat` 强转 `ChatRequest.parameters()→OpenAiChatRequestParameters`，而 `ChatRequest.builder().messages(...).build()` 产 `DefaultChatRequestParameters`→ClassCastException（sync throw → chatRawStream catch → handler.onError → OutputStep "流式失败回退阻塞"）。**为何阻塞 execute 不中招**：`OpenAiChatModel.chat` 不强转，唯流式 `doChat` 强转。**修**（TDD `LangChain4jModelExecutorStreamingBodyCaptureTest` RED→GREEN）：stream 建 per-request `OpenAiChatRequestParameters`（**自包含**：modelName + maxOutputTokens + customParameters，坑②：per-request 覆盖 model default→缺 modelName 则 body 缺 model→SF 20015）设 `ChatRequest.builder().parameters(params)`。假 transport（`StreamingCapturingClient` 实现 streaming `execute(req,parser,listener)`）捕 body + 喂罐装 OpenAI SSE chunk 验 SSE round-trip（onComplete 非 onError）。**教训**：流式 leaf 须用 OpenAi 专属 params 类型，非通用 `ChatRequest.builder().messages().build()`。

**⚠️ 生产链断裂修正（2026-09-14，用户实测报"流式生成失败，回退阻塞 chatRaw reason=此 ModelExecutor 不支持流式"）**：初版链**漏了 `CircuitBreakingModelExecutor` 层**——生产 `@Bean ModelExecutor` = `CircuitBreakingModelExecutor(buildRoutingExecutor, breaker, triage)`（熔断装饰器包 RoutingModelExecutor），它只覆写 `execute`（熔断 allow/recordSuccess/recordFailure）**未覆写 `stream`**→命中 `ModelExecutor.stream` 默认 throw UOE→**每个 `/chat/stream` 全量回退阻塞**（无 token 流，与修前等价，仅多一行 WARN）。**为何单测没抓到**：`OutputStepTest` 把 `CapturingExecutor`（裸 ModelExecutor，有 stream）**直接当 `UnifiedModelGateway.executor` 注入**，绕过了生产装饰器链→GREEN 但生产断。real-SF 冒烟 env-gated/skipped 也没抓。**修**：`CircuitBreakingModelExecutor` 加 `stream` 覆写（镜像 execute 语义：OPEN→抛 CircuitOpenException 快速失败不调 delegate；放行→转发 `delegate.stream`，**包装 handler 记账**因流式异步回调非 try-catch：onComplete→recordSuccess 恢复半开探针；onError→仅 RuntimeException 经 triage，模型可用性失败 recordFailure，致命/工具不计入）。TDD 5 测（CircuitBreakingModelExecutorTest 6→11）RED→GREEN，全量 897 GREEN。**教训（铁律级）**：跨层能力（流式/熔断/重试）单测**必须用真实生产 @Bean 装配链**（CircuitBreakingModelExecutor 包 RoutingModelExecutor 包 LangChain4jModelExecutor），不能用裸桩绕过装饰器——否则 GREEN 掩盖生产断裂。`NoopModelExecutor`（llm.enabled=false 兜底）仍无 stream→UOE→onError→阻塞 fallback（可接受，Noop 无 LLM 本就不可流式）。

**全链（逐层）**：
1. **引擎无关 seam** `StreamingReplyHandler`（gateway.core，无 LC4j 类型：`onPartialResponse(String)` / `onCompleteResponse(String fullReply, int tokens)` / `onError(Throwable)`）——保 `ModelExecutor` 引擎无关。
2. **`ModelExecutor.stream`** default `throw UnsupportedOperationException`（旧执行器/Noop 不受影响）。
3. **`RoutingModelExecutor.stream`**：合成 id → `route.apiModel`，委派 `route.executor().stream(request, handler)`。
3.5. **`CircuitBreakingModelExecutor.stream`**（生产装饰层·修后补）：生产 `@Bean ModelExecutor` = 本类包 `RoutingModelExecutor`。镜像 `execute` 熔断语义：OPEN→抛 `CircuitOpenException` 快速失败不调 delegate（向上传→chatRawStream catch→onError→OutputStep 阻塞 fallback 有完整主备容灾）；放行→转发 `delegate.stream`，**包装 handler 记账**（流式异步非 try-catch）：onComplete→`recordSuccess`（半开探针恢复）；onError→仅 RuntimeException 经 triage，模型可用性失败 `recordFailure`，致命/工具不计入。**初版漏此层→生产全量回退阻塞（见上方 ⚠️ 修正段）**。
4. **`UnifiedModelGateway.stream`**：`budgetChecker.check` + 建 `LlmRequest`（透传 disableThinking + messages + tools）+ `executor.stream`（executor = 生产装配的 CircuitBreakingModelExecutor）。
5. **`ChatLlmService.chatRawStream`**：算 primary（路由规则优先 / 否则 selector）+ 单主 `FailoverPolicy` 占位（**流式主模型 only，无中途故障转移**——failover 不用于流式）+ `disableThinkingFor(intent)`（闲聊恒关 / 非闲聊由 `thinkingEnabled`）+ `gateway.stream`。**同步异常**（无可用模型 / 预算超限 / leaf 前置抛）→ 捕获转 `handler.onError`。
6. **`OutputStep` 流式分支**（emitter 非 NO_OP 即 `/chat/stream` 时触发）：`StreamingReplyHandler` 桥——`onPartialResponse→emitter.emit(new ProgressEvent.TokenChunk(token))`→`SseProgressEmitter` 发 `reply_chunk` SSE 实时 flush；`onComplete→setModelResponse + setFinalReply(securityFilter.filter)+Proceed`。**`onError`/异常→落下面阻塞 `chatRaw` fallback**（主备容灾 + Schema 校验，韧性不丢）。
7. **leaf `LangChain4jModelExecutor.stream`**：建 `OpenAiStreamingChatModel`（镜像 `execute`：baseUrl/apiKey/modelName/timeout/maxTokens/disableThinking→customParameters + httpClientBuilder seam）→ `model.doChat(ChatRequest, lc4jHandler)`，`StreamingChatResponseHandler` 适配：`onPartialResponse(String)→透传`；`onCompleteResponse(ChatResponse)→aiMessage().text()+tokenUsage().totalTokenCount()→引擎无关 handler`；`onError→透传`。

**基础设施**：`ProgressEvent` sealed 加 `TokenChunk(String)` record；`SseProgressEmitter` 加 `reply_chunk` eventName（jsonData 通用不变）。

**已知限制**：
- 流式**跳 Schema 校验**（自由文本回复；结构化抽取仍走阻塞路径含 Schema/reAsk）。
- leaf 流式**无 `toolSpecifications`**（流式仅自由文本回复；工具路径仍阻塞 `execute`）。
- **SSE-fake 单测延后**：leaf 的 LC4j SSE→handler 映射真打验未做单测（`ServerSentEvent` event-field/[DONE] 语义不确定），real-SF 冒烟 `LangChain4jModelExecutorStreamingSiliconFlowSmokeTest` 为权威验证（env-gated SF_KEY，`export SF_KEY=sk-... && ./mvnw -Dtest=...SmokeTest test`）。

**实跑**：`WORKFLOW_ENABLED=true LLM_ENABLED=true`（+ `export SF_KEY=...` 验真流式）后 curl `/chat/stream` `{"sessionId":"s1","message":"我要退货","userId":"10086"}` 见 `reply_chunk` 逐 token 事件。注意：无订单号→entity-gate 澄清话术短路（不进 LLM，无 token 流）；带订单号 `我要退货 ORD-001`→工作流→流式输出。

**Why**：用户报前端无流式 + 延迟大（"绝对不是大模型的问题"），要求输出步流式 + 阻塞 fallback。引擎无关 seam 设计使流式能力不耦合 LC4j（未来换引擎只改 leaf）。
**How to apply**：动流式链时——seam 在 gateway.core（`StreamingReplyHandler`/`ModelExecutor.stream`），leaf 适配在 `LangChain4jModelExecutor.stream`；任何引擎无关层勿引 LC4j 类型。流式失败必须 fallback 阻塞（保主备容灾）。关联 [[routeplan-design]](ProgressEmitter/SSE 基础) [[langchain4j-boot4-compat-findings]](LC4j 适配) [[phase-llm-primary-backup-breaker]](关思考透传) [[business-tools-workflow-dag]](实测根因+埋点)。
