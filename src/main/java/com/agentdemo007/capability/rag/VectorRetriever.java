package com.agentdemo007.capability.rag;

import java.util.List;

/**
 * 向量检索器（第四层·Top-K 召回入口，Phase 20 起 implements {@link Retriever}）。
 *
 * <p>把查询经 {@link EmbeddingService} 向量化后交 {@link VectorStore} 余弦 Top-K 检索，
 * 返回按相似度降序的 {@link RagFragment}（携带分数）。空查询/空库返回空，由上层降级为
 * {@code Degrade(RAG_SKIP)}（§5.12 RAG 行、deg-004）。引擎无关 seam：dev 后端为
 * {@link InMemoryVectorStore}，prod 覆盖为 pgvector/Redis。
 *
 * <p>Phase 20：作为 Hybrid 检索器（{@link HybridRetriever}）的**向量通道**，亦独立可用
 * （{@code RagStep} 经 {@link Retriever} seam 注入，dev 单测可直接构造本类）。
 */
public class VectorRetriever implements Retriever {

    private final EmbeddingService embedding;
    private final VectorStore store;

    public VectorRetriever(EmbeddingService embedding, VectorStore store) {
        this.embedding = embedding;
        this.store = store;
    }

    /**
     * 检索 Top-K。
     *
     * @param query 用户查询（空/null/空白 → 空列表）
     * @param topK  返回上限
     * @return 按分数降序的片段；空查询或空库返回空
     */
    public List<RagFragment> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        float[] vec = embedding.embed(query);
        return store.search(vec, topK);
    }
}
