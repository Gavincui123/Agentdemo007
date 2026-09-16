---
name: phase6-7-understanding-intent-design
description: Phase 6/7 会话理解层+意图路由层设计决策（降级语义/LLM收口/注入分层/PipelineContext跨层字段）
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-04T06:47:37.829Z
---

Phase 6（会话管理与理解层）+ Phase 7（意图识别与模型路由层）已实现，TDD 全绿（截至 2026-09-04 全量 204 测试，+65）。

**非显而易见的设计决策（未来会话需知）：**

1. **改写/摘要降级用 `Proceed` 不是 `Degrade`。** §5.12 表里"问题改写"行写"回退原问题继续（不阻塞）"——注意它**没有**"话术+短路"字样（对比 MODEL_DOWN/RATE_LIMITED 行明确写"+短路"）。所以改写失败/质量不达标是**行为级降级**（standardQuery=rawInput + Proceed），无话术、不标 degraded。RAG 行（RAG_SKIP）才有话术→用 Degrade(RAG_SKIP)。

2. **辅助 LLM 调用（摘要/改写/意图分类）走 `ChatLlmService.chat(prompt, Intent.CHIT_CHAT)`**——复用收口入口，路由到 SIMPLE 小模型通道。没有为辅助调用新建 seam（保持 §9.11 收口）。ChatLlmService + PromptSanitizer 是 Phase 6 在 GatewayConfig 新增的 bean（此前不是 bean，仅测试手工构造）。

3. **注入二次扫描（§5.3.1 规则后置）不归识别器。** IntentRecognizerImpl 只做 规则前置(含InjectionPatternRule,零LLM)→小模型→兜底。曾误加 post-scan injectionPatterns 参数，结果是死代码（与规则层同库则恒空操作）——已删除。"规则后置注入二次扫描"实际由 接入层 InputSecurityFilter(前置门) + Phase 12 输出安全过滤器(后置门) 多层覆盖。

4. **意图识别低置信/模型不可用 → `Degrade(UNKNOWN_INTENT)` + 兜底 OTHER 继续推进**（§5.12 意图识别行无"短路"即继续，兜底走 SIMPLE 默认路由）。注入意图 → `ShortCircuit(INJECTION)`（零 LLM）。无可用模型 → `ShortCircuit(MODEL_DOWN)`。

5. **PipelineContext 跨层依赖 gateway.config.RouteRule.RouteType 是被许可的**——它是"贯穿7层的唯一状态载体"（§5.14），需持有各层强类型字段（intent/routeType/selectedModelId/summary/standardQuery），故 common.pipeline→intent+gateway.config+session.model 是设计内单向依赖，无环。

**延后项：** 大模型兜底层（随 LangChain4j）；Nacos 托管关键词表/注入模式/置信度阈值/路由规则（随 NacosModelConfigSource）；语义级改写忠实度校验（随小模型流式接入）。规则当前在 [[phase7]] IntentConfig 内置默认。下一阶段 Phase 8（上下文构建工厂三层隔离）。详见 docs/DEVELOPMENT-PLAN.md。
