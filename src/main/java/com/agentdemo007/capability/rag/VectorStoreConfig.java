package com.agentdemo007.capability.rag;

import com.agentdemo007.session.rewrite.QueryEnricher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * RAG 引擎装配（第四层·dev 后端，引擎无关 seam）。
 *
 * <p>dev 用 {@link HashEmbeddingService}（确定性 token-bag）+ {@link InMemoryVectorStore}（内存余弦 Top-K，
 * Phase 20 兼 {@link KeywordIndex} 精确通道），保证无 pgvector/Redis/真实 Embedding 模型即可启动
 * （每步降级原则）。prod 以 {@code @ConditionalOnMissingBean} 覆盖：EmbeddingService→LangChain4j
 * Embedding 桥接，VectorStore→pgvector/Redis（随 LangChain4j 接入，§8 风险表延后）。
 *
 * <p>Phase 20 Hybrid RAG：主 {@link Retriever} bean 装配为 {@link HybridRetriever}（{@code @Primary}，
 * 向量+关键词融合），{@code RagStep} 经 {@link Retriever} seam 注入。prod 无关键词索引时
 * {@link KeywordIndex} 装配 {@link KeywordIndex#NO_OP}（关键词通道空 → Hybrid 回退纯向量，②降级）。
 * 召回/校验/重排/注入扫描为引擎无关的链路组件，dev/prod 共用；仅向量化与存储后端可替换。
 */
@Configuration
public class VectorStoreConfig {

    /**
     * dev 稠密嵌入：HashEmbedding（假向量，不调 API）。prod {@code embedding.enabled=true} 时
     * 由 {@link EmbeddingConfig} 装配 {@link SiliconFlowEmbeddingService}+主备（本 bean 退让）。
     * 属性门控互斥（非 bean 存在性），与 LlmConfig/GatewayConfig 同构——避免 bean override 竞态。
     */
    @Bean
    @ConditionalOnProperty(prefix = "embedding", name = "enabled", havingValue = "false", matchIfMissing = true)
    EmbeddingService embeddingService() {
        return new HashEmbeddingService();
    }

    /**
     * dev 内存库，兼 {@link VectorStore} + {@link KeywordIndex}（同语料双用）。
     *
     * <p>返回具体类型 {@link InMemoryVectorStore} 以便作为 {@link KeywordIndex} bean 被
     * {@code @ConditionalOnMissingBean(KeywordIndex.class)} 正确识别 + 注入 {@link HybridRetriever}。
     * 与真库 {@code ChromaStoreConfig}（{@code vectorstore.type=chroma} 装配 {@code ChromaVectorStore}
     * + {@code ChromaRagCorpus}）属性门控互斥（非 bean 存在性）——装配顺序无关、可调试。
     */
    @Bean
    @ConditionalOnProperty(prefix = "vectorstore", name = "type", havingValue = "inmemory", matchIfMissing = true)
    InMemoryVectorStore vectorStore(EmbeddingService embedding) {
        return new InMemoryVectorStore(embedding);
    }

    @Bean
    VectorRetriever vectorRetriever(EmbeddingService embedding, VectorStore store) {
        return new VectorRetriever(embedding, store);
    }

    /**
     * 关键词索引：dev 由 {@link InMemoryVectorStore} 兼任（已是 KeywordIndex bean → 此 NO_OP 不生效）；
     * prod {@code VectorStore} 不实现 KeywordIndex 时装配 NO_OP（关键词通道空，Hybrid 回退纯向量，②降级）。
     */
    @Bean
    @ConditionalOnMissingBean(KeywordIndex.class)
    KeywordIndex keywordIndex() {
        return KeywordIndex.NO_OP;
    }

    /**
     * Phase 21 稀疏检索器（dev）：BM25 全语料打分召回（经 {@link RagCorpus} 取语料，引擎无关）。
     * 恒用 BM25 评分（与重排 bean 无关——稀疏召回的定义即 BM25；prod 重排可换 SiliconFlow）。
     * 真库模式（{@code vectorstore.type=chroma}）本 bean 退让——稀疏通道由 {@code ChromaStoreConfig}
     * 装配 {@code LuceneBm25Retriever}（磁盘倒排，不占堆内存）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "vectorstore", name = "type", havingValue = "inmemory", matchIfMissing = true)
    Bm25Retriever bm25Retriever(RagCorpus corpus) {
        return new Bm25Retriever(corpus, new Bm25Reranker());
    }

    /**
     * Phase 21 主检索器：Hybrid（稠密 {@link VectorRetriever} + 稀疏 {@link Bm25Retriever} 融合，
     * {@code @Primary}）。稠密通道异常→降级稀疏-only 继续（混合检索韧性）。{@code RagStep} 经
     * {@link Retriever} seam 注入本 bean。纯向量 {@link VectorRetriever} 仍为 bean（单测直接构造），不为主。
     * 真库模式（{@code vectorstore.type=chroma}）本 bean 退让，由 {@code ChromaStoreConfig} 装配
     * {@code ChromaHybridRetriever} 接管 @Primary（属性门控互斥）。
     */
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "vectorstore", name = "type", havingValue = "inmemory", matchIfMissing = true)
    Retriever hybridRetriever(VectorRetriever denseChannel, Bm25Retriever sparseChannel) {
        return new HybridRetriever(denseChannel, sparseChannel);
    }

    /**
     * 检索置信度终闸（双判据：被重排的看 relevance，未重排的看 cosine）。
     * min-score 语义按向量空间口径调：dev hash 0.3 缺省；真库部署建议 RAG_MIN_SCORE=0.4（真嵌入）。
     */
    @Bean
    RetrievalValidator retrievalValidator(
            @Value("${app.rag.min-score:0.3}") double minScore,
            @Value("${app.rag.min-count:1}") int minCount,
            @Value("${app.rag.rerank-min-score:0.3}") double rerankMinScore) {
        return new RetrievalValidator(minScore, minCount, rerankMinScore);
    }

    /**
     * 重排器：dev 用 {@link Bm25Reranker}（BM25-lite）；prod {@code reranker.enabled=true} 时
     * 由 {@code RerankerConfig} 装配 {@code SiliconFlowReranker}+主备容灾（本 bean 退让）。
     * 主备全失败降级回 BM25（重排只改顺序，降级安全不阻塞）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "reranker", name = "enabled", havingValue = "false", matchIfMissing = true)
    Reranker reranker() {
        return new Bm25Reranker();
    }

    @Bean
    RagInjectionScanner ragInjectionScanner() {
        return new RagInjectionScanner();
    }
}
