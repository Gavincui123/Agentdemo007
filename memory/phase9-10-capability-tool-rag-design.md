---
name: phase9-10-capability-tool-rag-design
description: Phase 9 工具调用 + Phase 10 RAG 设计决策（自纠正 Reparser seam/deg-009 vs deg-004 降级语义/引擎无关 seam/§5.14 RagFragment 不外泄/BM25-lite 重排）
metadata:
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-05T01:25:53.606Z
---

Phase 9（工具调用）+ Phase 10（RAG）已实现，TDD 全绿（截至 2026-09-05 全量 **342** 测试，+63 工具/+35 RAG）。

**非显而易见的设计决策（未来会话需知）：**

1. **§5.12 降级行 → StepOutcome 映射是关键契约，工具与 RAG 走不同终态。** 工具行"透传 LLM 自纠正；耗尽话术"→ `ShortCircuit(TOOL_FAILURE)`（deg-009，零 LLM 兜底）；RAG 行"跳过 RAG 继续（不阻塞）"→ `Degrade(RAG_SKIP)`（deg-004，标 degraded 但继续推进）。即 RAG 步骤**绝不短路、绝不阻塞**——召回空/校验不达标/注入扫描清空/链路异常四条路径全降级 `Degrade(RAG_SKIP)`，`ragFragments` 维持空（[[phase8-context-design]] 的 ObjectiveDataLayer 空段跳过）。这与 [[degradation-and-eval-principles]] 的收口语义一致。

2. **工具自纠正循环落在 `ToolExecutor`（有界），LLM 调用是延后 seam。** `Reparser` 函数式接口 `Optional<ToolCall> reparse(String feedbackPrompt)`：prod=`Reparser.NONE`（无 LLM→立即耗尽→抛 `ToolRecoverableException`→`ShortCircuit(TOOL_FAILURE)`）；测试 stub 模拟 LLM 改正→自纠正成功。即 Phase 5 `ToolErrorFeedback` 的"耗尽"分支在 prod=无 LLM 时立即触发，自纠正链路代码就绪、LLM 调用薄层延后（同 Phase 4 `ModelExecutor` 模式）。最大迭代 `app.tool.max-iterations:3`。

3. **ArithmeticEvaluator 手写递归下降，禁 eval（注入在词法层拦截）。** 文法 expr:=term(('+'|'-')term)* / term:=factor(('*'|'/')factor)* / factor:=('+'|'-')factor|primary / primary:=NUMBER|'('expr')'|sumExpr / sumExpr:='sum''('expr'..'expr')'。词法器只放行 数字/运算符/括号/点/'sum'，**字母→`ToolRecoverableException`**（`System.exit(0)` 这类在 evaluator 单测被拒；但 `ArithmeticDetector` 前缀未命中则根本不触发工具，正常走 LLM 对话）。BigDecimal 除法 10 位 HALF_UP。区间求和用等差求和公式（longValueExact 转 int）。

4. **§5.14 收口：`RagFragment`/`ToolCall` 是能力层内部强类型，绝不外泄到步骤间。** RAG 链路内（Embedding→检索→校验→重排→注入扫描）全流转 `RagFragment(text,score,source)`；最终**仅 `text()` 抽取为 `List<String>`** 写入 `PipelineContext.ragFragments`（Phase 8 消费者字段）。工具侧 `ToolCall` 同理，仅结果 String 入 `toolResults`。无 bespoke 步骤间结构。

5. **引擎无关 seam 扩展到 RAG（同 Phase 4 `ModelExecutor`）。** `EmbeddingService`/`VectorStore` 是接口，dev 落 `HashEmbeddingService`（DIMENSION=64 token-bag，ASCII 词累积/CJK 单字/标点空白分隔，L2 归一）+ `InMemoryVectorStore`（synchronized 余弦 Top-K，零范数查询→空）；`VectorStoreConfig` 用 `@ConditionalOnMissingBean` 留 prod 覆盖（LangChain4j Embedding/pgvector/Redis 桥接，延后避免过早引 langchain4j-core 重依赖）。

6. **Reranker dev 用 BM25-lite，可证明重排优于纯余弦召回。** 纯余弦被"词频锤击"误导（某片段反复重复某词即获高余弦）；BM25 的 IDF（稀有词高权重）+tf 饱和(k1=1.2)+长度归一(b=0.75) 抑制该现象，使含稀有查询词片段上浮。**复用 `HashEmbeddingService.tokenize`（已改包级可见）保证 token 口径一致**。prod Cross-Encoder/模型重排为覆盖薄层，延后。空查询→保留召回序（无重排信号）。

7. **RAG 片段注入是静默过滤降级，不是短路（与入口注入分工）。** 入口注入走 `ShortCircuit(INJECTION)` 零 LLM（deg-001，`InputSecurityFilter`）；RAG 片段注入走 `RagInjectionScanner` 静念剔除（"忽略之前指令"/"reveal system prompt"/"jailbreak"/"越狱" 等，大小写无关），纯净片段仍可进上下文。全被剔除→`Degrade(RAG_SKIP)`。词库可扩展，prod 可接模型分类器。

8. **@Order 槽位：Tool 650 / RAG 660，夹在 RouteDispatch(600) 与 ContextBuilder(700) 之间。** 601-699 槽位无碰撞。两步输入取 `standardQuery?.text() ?: rawInput`（与意图步骤一致）。`RagSeedRunner`（@ConditionalOnProperty matchIfMissing）启动索引 4 条客服 KB 片段，使 /chat 即开即用；`app.rag.seed.enabled=false` 关。

**eval 数据：** `eval/tool.json`（tool-001..006，覆盖四则运算/区间求和/除零 TOOL_FAILURE/非工具透传）、`eval/rag.json`（rag-001..005，基于种子语料，词面重叠召回/空召回 RAG_SKIP/空查询）。dev 哈希嵌入是词袋口径，查询需与片段词面重叠方可召回；prod 真实 Embedding 覆盖语义召回。

**延后项：** LangChain4j Embedding/ChatModel 桥接、pgvector/Redis 向量库、Cross-Encoder 重排、RAG 配置 Nacos 动态热加载（基线已落 application.yml：`app.rag.top-k/min-score/min-count/seed.enabled`、`app.tool.max-iterations`）。详见 docs/DEVELOPMENT-PLAN.md Phase 9/10。
