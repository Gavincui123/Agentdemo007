package com.agentdemo007.capability.rag;

import java.util.List;

/**
 * 重排器 seam（第四层·召回后重排，引擎无关抽象）。
 *
 * <p>统一重排出口：dev 用 {@link Bm25Reranker}（确定性 BM25-lite，无真实模型）；
 * prod 经 {@code reranker.enabled=true} 装配 {@code SiliconFlowReranker}（真模型重排）+
 * 主备容灾（{@code FailoverReranker} 逐 provider 尝试，全失败→降级 {@link Bm25Reranker}——
 * 重排只改顺序，降级安全不阻塞、不污染向量空间）。{@code RagStep} 只依赖本接口，
 * 换重排引擎不换收口（④统一收口，§5.14）。
 */
public interface Reranker {

    /**
     * 在候选集上重排。
     *
     * @param query     用户查询（空/null/空白 → 实现可保留召回序）
     * @param candidates 召回候选片段（按召回序）
     * @return 重排后的片段（同分稳定保留召回序）；空候选返回空
     */
    List<RagFragment> rerank(String query, List<RagFragment> candidates);
}
