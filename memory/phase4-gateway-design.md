---
name: phase4-gateway-design
description: Phase 4 统一模型网关架构与 LangChain4j 延后决策（第六层引擎无关内核）
metadata:
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-04T16:47:42.174Z
---

Phase 4 统一模型网关（第六层）做成**引擎无关内核**，LLM 引擎（LangChain4j）延后接入：

- **`ModelExecutor` 是引擎边界**（`execute(LlmRequest): LlmResponse`，强类型 DTO，不传各引擎原生对象）。dev 用 `NoopModelExecutor`（占位降级回复）保证无真实 LLM 也能启动 + contextLoads 绿；prod 用 `@ConditionalOnMissingBean(ModelExecutor.class)` 覆盖为 LangChain4j 桥接适配器。
- **LangChain4j 延后**：`langchain4j-redis`/`langchain4j-jackson` 早在 Phase 3 即延后（Jackson 2/3 冲突），Phase 4 进一步把 `langchain4j-core`（拉 Jackson 2 + OkHttp 重依赖）的 `LlmConfig` + `CompositeLlmCallbackHandler`（流式回调）也延后。理由：`ModelExecutor` 边界已就绪，桥接是薄层，待有真实端点/密钥再接。**Why**：避免过早引入重依赖且无法对真实 LLM 单测。**How to apply**：后续接 LangChain4j 时只写一个 `LangChain4jModelExecutor implements ModelExecutor`，不动网关内核；流式随其流式契约扩展。
- **网关三段流**：`TokenBudgetChecker.check`（超速率/超日 Token→`RateLimitExceededException`，零 LLM，话术短路 HTTP 200 不返回 429）→ `FailoverExecutor.execute`（主→备选逐个尝试，全失败→`LlmUnavailableException`/FAILOVER_EXHAUSTED）→ `record` 记账。
- **选模型**：`RouteRule` 有 `targetModelId` 则直接用；否则 `ModelSelector`（TagBased@Primary / WeightBased / CostAware）在启用模型中选。`ChatLlmService.chat(prompt, intent)` 强制 `PromptSanitizer` 包裹后入网关。
- 测评：`eval/gateway.json`（gw-001~005）+ `eval/degradation.json` deg-008（RATE_LIMITED）。

Phase 4 内核 97 绿（含 Phase 1-3）。相关：[[degradation-and-eval-principles]]、[[spring-boot-4-jackson3-gotchas]]

**2026-09-05 code-review 修正（gateway 相关，3 项）**：
- `TokenBudgetChecker` 注入 `LongSupplier` 时钟；日 Token 配额按 **UTC epochDay**（`millis/86_400_000`）跨天清零——旧实现 `tokensToday` 只增不减，跨天即永久 RATE_LIMITED。速率窗口仍按 `window()` 重置。
- `FlowControlPolicy` 删除 `maxConcurrent` 死字段（计划 §4 流控为 RPM/TPM/成本，无并发维度；`TokenBudgetChecker` 无成对生命周期维护并发计数）。如需并发控制，在 `UnifiedModelGateway.invoke` 引入 try/finally 计数后补回。
- `ModelSelector` 策略可配置切换：`GatewayConfig.selectModelSelector(strategy)` 工厂 + `@Primary` 单 bean，`app.model-selector.strategy=tag|weight|cost`（默认 tag）属性驱动；删去三个固定 named bean。
- 全量 244 绿（226→+18 新测试）。