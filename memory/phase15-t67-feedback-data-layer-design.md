---
name: phase15-t67-feedback-data-layer-design
description: Phase 15 T67 微调闭环数据层：FeedbackCollector+OfflineDataPool seam+TrainingSample 强类型+②降级副信道（527测试）
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-07T11:00:43.999Z
---

Phase 15 T67（微调闭环·数据层，527 测试）：

**闭环**：`POST /feedback` → FeedbackCollector → OfflineDataPool →（T68 FineTuningPipeline drain → train → ModelRegistrar 注册）。

**记录（强类型，§5.14 非 Map）**：
- `FeedbackLabel` enum：POSITIVE/NEGATIVE（训练信号）。
- `TrainingSample(traceId, sessionId, prompt, reply, label, comment, collectedAt)` — 池条目。
- `FeedbackRequest(traceId, sessionId, prompt, reply, label, comment)` — 入参，客户端携带当轮对话上下文（解耦 JPA lookup；prod 可换 traceId 查 ChatTurnRepository 的 seam）。
- `FeedbackResponse(boolean collected)` — 出参。

**OfflineDataPool seam**（`com.agentdemo007.feedback`，§5.14 引擎无关）：`store/drain/size`。dev `InMemoryOfflineDataPool`（`ConcurrentLinkedDeque`，drain 原子取出+清空）。prod JPA/对象存储覆盖。形态对齐 [[phase15-t66-route-weight-design]] 的 ConfigWriter：seam 在前，dev 占位，prod 后接。

**FeedbackCollector**：plain class（非 @Component）+ @Bean 工厂（FeedbackConfig），对齐 TokenBudgetChecker 的可注入时钟模式（`Supplier<OffsetDateTime> clock`，1-arg→2-arg 委托 OffsetDateTime::now）。collect 组装 TrainingSample→pool.store，try/catch 吞而不抛（②降级：反馈是副信道，落池失败返回 false 不影响主链路）。

**FeedbackController**（`@RestController POST /feedback`）：校验 traceId/prompt/reply 非空+label 非空→400 BAD_REQUEST；collect→恒 HTTP 200+code=0，collected 透出（②降级：落池失败不 5xx，仅 collected=false）。对齐 ChatResponse 的 code 恒 0+标志位模式。鉴权留 T72。

**Why**：满足"在线反馈→数据池"半闭环；②降级保证反馈副信道永不影响 /chat 主链路。
**How to apply**：同类"在线副信道采集+离线池"复用此 seam+强类型 record+②降级吞异常模式；可注入时钟用 plain class + @Bean（非 @Component，避免强加 Supplier bean）。
