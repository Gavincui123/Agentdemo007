package com.agentdemo007.capability.rag;

import java.util.List;

/**
 * 检索器 seam（第四层·召回入口，Phase 20 引擎无关抽象）。
 *
 * <p>统一召回出口：向量通道（{@link VectorRetriever} 余弦 Top-K）、Hybrid 通道
 * （dev {@link HybridRetriever} / 真库 {@code chroma.ChromaHybridRetriever}：稠密+BM25 稀疏双通道融合）
 * 实现同一契约，{@code RagStep} 只依赖本接口，换检索策略不换收口（④统一收口，§5.14）。
 * {@code retrieve(query, topK)} 的 {@code query} 为上游 {@code session.rewrite.QueryRewriter}
 * 产出的改写后标准化 Query（{@code standardQuery}）——查询改写在管道前段收口，检索器不自取。
 */
public interface Retriever {


    /**
     * 召回 Top-K 候选片段。
     *
     * @param query 改写后的标准化查询（空/null/空白 → 空列表）
     * @param topK  返回上限
     * @return 候选片段（可空实现内排序）
     */
    List<RagFragment> retrieve(String query, int topK);
}
