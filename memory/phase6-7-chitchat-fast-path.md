---
name: phase6-7-chitchat-fast-path
description: "chit-chat 零LLM快通道+关键词配置化已实现(708测GREEN)：KeywordTriageStep(@Order150)真正前置+QueryRewriter/IntentRecognitionStep/RagStep三处skip守卫+IntentKeywordProperties配置化；修实跑\"你好\"误报\"降级·系统仍答·RAG_SKIP\"+被改写器调一次小模型(~3.4s)两大病"
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-10T06:44:58.180Z
---

chit-chat 零 LLM 快通道 + 关键词配置化 **已实现**（708 测 GREEN，2 smoke 跳过）。修实跑"你好"两大病：①误报"降级·系统仍答·RAG_SKIP"②被改写器无条件调一次小模型(~3.4s)。详见 [[phase6-7-understanding-intent-design]]（原设计）+ [[degradation-and-eval-principles]]。

**根因（三处）：**
- **关键词层不是真第一层**：`IntentRecognizerImpl.recognize` line 50 `if(rule.isPresent()) return` 确实零 LLM，但它在 `IntentRecognitionStep(@Order 500)`，而 `QueryRewriter(@Order 300)` 排前且对**每请求无条件 `llm.chat(prompt, CHIT_CHAT)`**（无跳过守卫）→ "你好"先被改写调一次小模型(~3.4s)，关键词层没机会先短路。
- **chit-chat 的 RAG_SKIP 被误判降级**：`RagStep` 对 "你好" 召回空(与客服语料不相关)→ `markDegraded(RAG_SKIP)`+`Degrade(RAG_SKIP)`→ `degraded=true`→ 前端显示"降级·系统仍答·RAG_SKIP"。但 chit-chat 本不需知识库，跳过 RAG 是**正常**非降级。
- **关键词硬编码未配置化**：`IntentConfig.ruleMatcher()` 写死 11 条，注释挂"Nacos 覆盖"但从未接线→用户无法加词、没人告知怎么配。

**修复（TDD 5 切片 + 集成）：**
1. `KeywordTriageStep`(@Order 150，**流水线第一步**)：对 `rawInput` 跑 `RuleMatcher`——命中 CHIT_CHAT 且置信达标→设 intent+confidence 返回 Proceed(零 LLM)；命中 INJECTION→`ShortCircuit(INJECTION)`(比 IntentRecognition 更早，连改写都省)；无命中/冲突/低置信→Proceed 不设 intent 交后续。读 rawInput(此时 standardQuery 未产出)→对原始输入直接命中。
2. `QueryRewriter`(300)：`intent==CHIT_CHAT`→跳过 `llm.chat`，`standardQuery=StandardQuery.of(rawInput)`+`enricher.enrich(rawInput)`，Proceed。**只对 CHIT_CHAT 跳**——其他 triaged intent(如 REASONING)仍需改写补上下文/消指代。
3. `IntentRecognitionStep`(500)：`intent!=null`(triage 已设)→跳过 `recognize`，**仍 `metrics.recordIntent`**(observability 收口不漏统计)，Proceed。注入已在 triage 短路不到此→此处 intent 非 null 必为 triage 高置信非注入，skip 安全。
4. `RagStep`(660)：`intent==CHIT_CHAT`→`Proceed`(**非 Degrade**)，不调 retriever 不 markDegraded。消误报 banner。deg-004 RAG_SKIP 适用"需 RAG 却召回失败"的意图；chit-chat 本不该走 RAG，跳过=正常 Proceed。
5. `IntentKeywordProperties`(`@ConfigurationProperties(prefix="intent.keywords")`)：`List<RuleDef>{keyword,intent,confidence=0.85}`。`IntentConfig.ruleMatcher(props)`：rules 非空→**替换**默认 11 条(运维拥有完整列表)；空/缺省→默认。`InjectionPatternRule` 恒内置追加(安全：注入词表不配置化)。`@EnableConfigurationProperties` on IntentConfig。application.yml 加 `intent.keywords` 注释示例(含全默认+意图枚举)。

**关键设计点 / 坑：**
- **"已 triage"信号 = `context.intent()!=null`**：IntentRecognitionStep 据此跳过重识别。triage 只在高置信时设 intent(低置信不定论交小模型)。
- triage 读 rawInput、IntentRecognition 读 standardQuery——两者查询源不同但 keyword 对原始/改写都命中("你好"两处都含)。
- **Spring 7 `NoResourceFoundException` 构造器 3 参**`(HttpMethod, String resourcePath, String message)`——不是 2 参(踩坑)。chrome devtools 404 噪声修复：`GlobalExceptionHandler` 加专用 `@ExceptionHandler(NoResourceFoundException.class)`→404+DEBUG，不再落 `handleUnexpected` 记 ERROR+500(此前每开页面刷一条 ERROR+堆栈，因 Chrome 探测 `/.well-known/appspecific/com.chrome.devtools.json`)。
- 全量 708 GREEN：KeywordTriageStepTest(5)+QueryRewriter(1)+IntentRecognitionStep(2)+RagStep(1)+IntentConfigKeywordTest(3)+PipelineOrchestratorTest 集成(1,triage→RagStep 链断言"你好"不误报降级+不调召回)+GlobalExceptionHandlerNoResourceTest(1)。

**配置用法**（告知用户）：在 Nacos dataId 或 `application-{profile}.yml` 写 `intent.keywords.rules`（非空=替换默认 11 条）。意图枚举：`CHIT_CHAT/REASONING/LONG_CONTEXT/STRUCTURED_EXTRACTION/TRANSFER_TO_HUMAN`。示例见 application.yml 注释。
