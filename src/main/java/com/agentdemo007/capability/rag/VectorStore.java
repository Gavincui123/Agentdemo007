package com.agentdemo007.capability.rag;

import java.util.List;

/**
 * 向量库（第四层·RAG 检索后端，引擎无关 seam）。
 *
 * <p>{@link #index} 嵌入并存储片段；{@link #search} 按余弦相似度降序返回 Top-K，
 * 返回片段携带相似度分数。dev 用 {@link InMemoryVectorStore}（内存 + 余弦），
 * prod 覆盖为 pgvector / Redis vector 桥接（随 LangChain4j 接入，§8 风险表：可在两者间切换）。
 */
public interface VectorStore {

    /** 嵌入并索引片段。 */
    void index(List<RagFragment> fragments);

    /**
     * 按相似度检索 Top-K。
     *
     * @param queryVector 查询向量（须非零范数；零范数返回空）
     * @param topK        返回上限
     * @return 按分数降序的片段（携带相似度分数）
     */
    List<RagFragment> search(float[] queryVector, int topK);
}
