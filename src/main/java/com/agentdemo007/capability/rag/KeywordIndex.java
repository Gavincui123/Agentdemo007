package com.agentdemo007.capability.rag;

import java.util.List;

/**
 * 关键词索引 seam（第四层·Phase 20 Hybrid RAG 精确通道）。
 *
 * <p>按精确词（订单号/型号/发票类型，来自 {@link com.agentdemo007.session.model.QueryEnrichment#keywords()}）
 * 做子串/精确匹配召回——补向量余弦在精确标识符上的漂移（订单号语义模糊但词面精确）。
 * dev 由 {@link InMemoryVectorStore} 兼任（同语料扫描）；prod 覆盖为 BM25/ES 关键词索引，
 * 不实现时装配 {@link #NO_OP}（关键词通道空 → Hybrid 回退纯向量，②降级不阻塞）。
 */
public interface KeywordIndex {

    /** 空实现：无关键词索引时返回空，Hybrid 回退纯向量通道（②降级）。 */
    KeywordIndex NO_OP = (keywords, topK) -> List.of();

    /**
     * 按精确词召回（子串/精确匹配）。
     *
     * @param keywords 精确词（来自 QueryEnrichment）
     * @param topK     返回上限
     * @return 命中片段（含任一关键词，按命中数降序）
     */
    List<RagFragment> searchByKeywords(List<String> keywords, int topK);
}
