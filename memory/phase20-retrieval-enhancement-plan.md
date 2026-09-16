---
name: phase20-retrieval-enhancement-plan
description: "Phase 20 已实现(T88-T93 GREEN,633测)：约束改写(QueryEnrichment 只补不替/不下结论/RewriteQualityChecker 业务结论守卫) + Hybrid RAG(Retriever/KeywordIndex seam+HybridRetriever @Primary) + 时效治理(RagFragment temporal 字段+displayText 标注+Reranker 衰减) + eval 真断言(queryEnrichmentContains/ragFragmentsContain/hasFragments) + RAG citation 全链路(ragCitations 强类型收口非混入 reply/可追溯≠正确)"
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-09T02:00:32.597Z
---

Phase 20 已 TDD 落地（T88-T92 全 GREEN，全量 594 测零回归，39 新测）。计划见 `docs/DEVELOPMENT-PLAN.md` §Phase 20（任务清单全 `[x]` + 实现注记）。

**T88 约束改写：** `QueryEnrichment(keywords, timeline)` 收口于 `PipelineContext`（§5.14 强类型非 Map，缺省 `EMPTY` 非空）。`QueryEnricher`（@Component）确定性抽取——正则 ASCII 订单号/型号（含数字、≥4、排除 4 位年份）+ CJK 词典精确词（发票类型）+ 时间线正则，**只抽事实不下结论**；prod 覆盖 NER。`QueryRewriter` 2 参构造（@Autowired enricher）+ 1 参兜底（`new QueryEnricher()`，旧测试零改动），产补全槽**不覆盖** standardQuery（原查询经 rawInput 保留），prompt 增"保留原意/不下结论"。`RewriteQualityChecker` 增 `BUSINESS_CONCLUSION_MARKERS`（已发货/已退款/已到账/已完成 等）守卫——改写含标记而原问题未提及→回退（用户原话提及时保留不算新增结论）。

**T89 Hybrid RAG：** `Retriever` seam（`retrieve(query,topK)`）统一召回出口；`VectorRetriever implements Retriever`；`HybridRetriever implements Retriever` 融合向量+关键词（精确词命中优先置顶，无精确词→关键词通道空→回退纯向量 ②降级）。`KeywordIndex` seam——dev 由 `InMemoryVectorStore` 兼任（同语料子串匹配），prod 不实现时装配 `NO_OP`。`vectorStore` @Bean 返回具体 `InMemoryVectorStore` 以作 KeywordIndex bean 被识别。`HybridRetriever` `@Primary`，`RagStep` 字段 `VectorRetriever→Retriever`（既有 RagStepTest 零改动）。规则路由=精确词命中优先+BM25 稀有词高 IDF 自然上浮（不另造 boost 标记，避外泄 bespoke 结构违 §5.14）。

**T90 时效标注（§5.14 关键决策）：** 原设计拟 `ObjectiveDataLayer` 做标注，但 §5.14 收口 `ragFragments: List<String>`（不外泄 RagFragment），改 `List<RagFragment>` 触 PipelineContext/ObjectiveDataLayer/ContextMerger/eval/context.json(7处含 JSON 反序列化)大辐射且违收口。**故标注落在 RagStep→List<String> 抽取边界**：`RagFragment.displayText()` 历史片段前缀"【历史参考资料·截至{date}】"（date=validUntil 或 timestamp 格式 yyyy-MM-dd），当前/无标注→原文；ObjectiveDataLayer 既有 RAG_HEADER 外层隔离不变，per-fragment 标注内嵌文本串——模型所见等价，§5.14 不破。`RagFragment` 增 `timestamp`/`validUntil`/`temporalTag`（3 参构造保留，temporal 经 search/searchByKeywords/rerank 全链路透传——3 参会丢 temporal，均改全参构造）。

**T91 时效衰减：** `Reranker` 对 `temporalTag=HISTORICAL` 片段 BM25 分乘 `TEMPORAL_DECAY=0.5`（同等 BM25 近期优先）。用 `temporalTag`（显式）非 `validUntil`-vs-now——避免注入时钟时序依赖（indexer/测试直接设 HISTORICAL，确定性）。`validUntil`/`timestamp` 仅供标注。

**T92 ③环节测评真断言（关键发现）：** 既有 `EvalExpected` 的 `standardQueryContains`/`hasFragments`/`summaryTriggered` 被 `FAIL_ON_UNKNOWN_PROPERTIES=false` **静默丢弃——文档字段从未被对照**（hollow eval）。故扩 `EvalExpected`（+`queryEnrichmentContains`/`ragFragmentsContain`/`hasFragments`，10 参次级构造保留既有调用方）+ `ActualOutcome`（+3 派生）+ `compare`（`checkContains`/`checkAnyContains`）。新增 `EvalExecutorPhase20Test`（fake 执行器设 queryEnrichment/ragFragments）验对照逻辑。golden 用例 und-006/007 + rag-006/007 + RagSeedRunner 增精确词/历史种子。**已知限制**：eval 用例在测试套件仅由 `GoldenSuiteTest`（trivial 执行器）加载冒烟——不跑真实流水线、不断言通过率（dev 无真实 LLM，通过率属部署门禁）。

**T93 RAG citation 全链路（§5.14 收口 + 可追溯≠正确，2026-09-09）：** §5.14 曾让 `RagFragment.source`（来源标识，种子已填 kb-refund 等）在 `displayText()` 抽取边界丢弃——source 载有但无处可追（不进 prompt/回答/审计）。闭环：`RagStep` 命中时格式化 `[来源: {source}] {displayText}` 存 `context.ragCitations`（**List<String> 强类型收口，非 Map、非塞 reply 字符串**，符合④）→ `PipelineResult.citations`（`ok`/`degraded` 重载传，`shortCircuit` 传空——话术不背书出处）→ `ChatResponse.citations` → 前端 `parseChatResponse` 数组守卫过滤 → `store ChatMessage.citations`（send/onTurn 存）→ `MessageBubble` 来源区（编号列表，空不显示，标注「参考来源 · 可追溯不等于绝对正确」）。审计：`RagStep` log 记命中 source 列表（运维侧可追溯）。**重载工厂** `ok(reply)`/`degraded(reply,scenario)` 保留兼容既有 ~15 桩（单参→默认空 citations），新增 `ok(reply,citations)`/`degraded(reply,scenario,citations)`。5 新测（RagStep citation 3 + Orchestrator 1 + ChatController 1）+前端 chat.test citations 2。**633 后端 + 56 前端 + vue-tsc -b GREEN**。

**产出**：session/model/QueryEnrichment、session/rewrite/QueryEnricher；common/pipeline/PipelineContext(+queryEnrichment)；session/rewrite/QueryRewriter(2参+补全+prompt)、RewriteQualityChecker(业务结论守卫)；capability/rag/Retriever、KeywordIndex、HybridRetriever、VectorRetriever(implements Retriever)、InMemoryVectorStore(implements KeywordIndex+透传)、Reranker(透传+衰减)、RagFragment(temporal+displayText)、RagStep(Retriever seam+displayText)、VectorStoreConfig(@Primary Hybrid+NO_OP KeywordIndex)、RagSeedRunner(精确词/历史种子)；eval/EvalExpected、ActualOutcome、EvalExecutor(+Phase20 对照)、understanding.json、rag.json。

相关：[[phase17-18-plan]]、[[phase9-10-capability-tool-rag-design]]、[[phase6-7-understanding-intent-design]]、[[phase8-context-design]]、[[degradation-and-eval-principles]]。
