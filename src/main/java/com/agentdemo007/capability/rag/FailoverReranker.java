package com.agentdemo007.capability.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 重排主备容灾（第四层·重排韧性）。
 *
 * <p>持有有序 API provider 列表（主在前）+ BM25 兜底。{@code rerank} 逐 API provider 尝试：主成功即返回，
 * 主失败→切备；主备<b>全失败→降级 {@link Bm25Reranker}</b>（重排只改顺序，降级安全不阻塞、不污染向量空间
 * ——与 embedding 不同：embedding 不可回退 Hash，因向量空间不一致；重排无此约束）。
 *
 * <p>空查询/空候选保留召回序、不调 API（无信号不重排，契约同 BM25）。
 */
public class FailoverReranker implements Reranker {

    private final List<Reranker> apiProviders;
    private final Reranker fallback;

    public FailoverReranker(List<Reranker> apiProviders, Reranker fallback) {
        this.apiProviders = apiProviders == null ? List.of() : apiProviders;
        this.fallback = fallback != null ? fallback : new Bm25Reranker();
    }

    @Override
    public List<RagFragment> rerank(String query, List<RagFragment> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return new ArrayList<>(candidates); // 无信号保留召回序
        }
        for (Reranker p : apiProviders) {
            try {
                return p.rerank(query, candidates);
            } catch (RuntimeException e) {
                // 主失败→切备
            }
        }
        return fallback.rerank(query, candidates); // 主备全失败→降级 BM25（重排只改顺序，安全）
    }
}
