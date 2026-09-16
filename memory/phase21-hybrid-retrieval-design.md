---
name: phase21-hybrid-retrieval-design
description: Phase 21 真混合检索：真稠密(SiliconFlow embedding)+真稀疏(BM25 检索器)融合，主备容灾，稠密挂→稀疏兜底，全693测GREEN
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-10T02:59:46.503Z
---

Phase 21 把 RAG 检索从「假稠密(Hash)+关键词子串」升级为**真混合检索**（用户明确要求：稀疏+稠密，单 BM25 没法用）。全量 693 测 GREEN（2 smoke gated）。

**架构（双 Retriever 通道融合，收口不变）：**
- 稠密召回 `VectorRetriever`→`EmbeddingService`→`InMemoryVectorStore` 余弦。
- 稀疏召回 **新** `Bm25Retriever implements Retriever`：全语料 BM25 打分（经 `RagCorpus` seam 取语料，引擎无关；dev=InMemoryVectorStore implements RagCorpus 单库双用，prod=ES）。BM25 评分**复用** `Bm25Reranker`（同公式+分词+时效衰减），检索器=全语料召回、重排器=候选集重排。
- `HybridRetriever` **重写**为 `(Retriever dense, Retriever sparse)` 双通道（**丢** QueryEnricher/KeywordIndex 通道，BM25 高 IDF 自然覆盖精确词）；融合：稀疏优先置顶+稠密补齐+去重。

**降级语义（关键，用户拍板）：**
- 稠密通道异常（embedding 主备全挂）→ HybridRetriever **捕获、降级稀疏-only 继续**（不 RAG_SKIP、不回退 Hash——索引真向量与查询 hash 向量空间不一致，余弦无意义；稀疏兜底是混合检索的韧性）。
- reranker 主备全失败→降级 BM25（重排只改顺序，安全不污染向量空间）。

**主备容灾（镜像 LLM FailoverExecutor）：**
- `SiliconFlowEmbeddingService`(叶子,POST /v1/embeddings body{model,input,encoding_format:"float"}→data[0].embedding) → `FailoverEmbeddingService`(逐 provider，全失败→抛→稀疏兜底)。
- `SiliconFlowReranker`(叶子,POST /v1/rerank body{model,query,documents,top_n,return_documents:false}→results[].index+relevance_score 重排) → `FailoverReranker`(逐 provider，全失败→BM25)。

**装配（属性门控互斥，非 @ConditionalOnMissingBean）：**
- `EmbeddingConfig`(@ConditionalOnProperty embedding.enabled=true) vs `VectorStoreConfig.embeddingService`(havingValue=false,matchIfMissing=true→HashEmbedding)。同构 reranker。**必须属性门控**——两 @Configuration 同名 bean 用 @ConditionalOnMissingBean 会 bean-override 竞态（处理顺序相关）；属性门控互斥无竞态（镜像 LlmConfig/GatewayConfig）。
- `RagConfigBootstrap`(CommandLineRunner,ObjectProvider null-safe)+`RagConfigReporter`(纯函数) 启动打印 embedding/reranker provider 脱敏报告（收口）。`LlmConfigReporter.maskKey` 改 public 复用。

**重构：** `Reranker` 具体类→接口；BM25 逻辑改名 `Bm25Reranker implements Reranker`(dev+兜底)。blast radius: `new Reranker()`×6→`new Bm25Reranker()`(RerankerTest/RagStepTest×4/RerankerTemporalDecayTest/RagStepTemporalFramingTest/RagTemporalPreservationTest)+VectorStoreConfig。`InMemoryVectorStore` 加 `fragments()`(implements RagCorpus)。`RagSeedRunner` 不动（Bm25Retriever 读同一 store）。

**官方文档核实（用户强制）：** `Qwen/Qwen3-Embedding-8B`✓(32768 token,可选 dimensions)、`Qwen/Qwen3-Reranker-8B`✓(可选 instruction)。硅基流动 docs 是 JS SPA，WebFetch 取不到——用户贴文确认。

**坑：** 用户 Nacos 的 `reranker` 块**漏 `enabled` 字段**→不激活 RerankerConfig→reranker 走 dev BM25。必须加 `reranker.enabled: true`。BM25 等分测试 fixture：2 候选（退款退款退款退款 vs 流程办理须知）两词 df 均 1（等 IDF）→ tf 决定→锤击片段胜，**不是**稀有词胜；要 4 候选（退款×3+流程×1）让退款低 IDF/流程高 IDF 才能验稀有词上浮。

关联 [[phase-llm-primary-backup-breaker]]（Part A 意图驱动关思考已并入本基线，693 测）、[[phase20-retrieval-enhancement-plan]]（HybridRetriever 从关键词子串→真 BM25 的演进）。
