package com.agentdemo007.capability.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * BM25 稀疏检索器（第四层·prod 稀疏召回通道，真 BM25 全语料打分）。
 *
 * <p>实现 {@link Retriever}，对全语料（经 {@link RagCorpus} 取全部已索引片段）以 BM25 打分召回 Top-K：
 * IDF（稀有词高权重）+ tf 饱和 + 长度归一——稀有查询词（订单号/型号等精确标识符 IDF 极高）自然上浮，
 * 与稠密通道（{@link VectorRetriever} 语义余弦）互补：稠密捕语义近似、稀疏捕词面精确，融合为真混合检索。
 *
 * <p>BM25 评分<b>复用</b> {@link Bm25Reranker}（同一分词口径 + 公式 + 时效衰减），不重复实现——
 * 检索器与重排器同构打分，仅语料范围不同（检索器=全语料召回、重排器=候选集重排）。
 *
 * <p>引擎无关 seam：dev 读 {@link InMemoryVectorStore}（implements {@link RagCorpus}，单库双用）；
 * prod 拆独立 BM25/ES 索引（换 {@link RagCorpus} 实现即可，召回出口 {@link Retriever} 不变）。
 */
public class Bm25Retriever implements Retriever {

    private final RagCorpus corpus;
    private final Bm25Reranker bm25;

    public Bm25Retriever(RagCorpus corpus, Bm25Reranker bm25) {
        this.corpus = corpus;
        this.bm25 = bm25;
    }

    @Override
    public List<RagFragment> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of(); // 无检索信号
        }
        List<RagFragment> all = corpus.fragments();
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        // 检索位打分：BM25 打分副本（cosineScored=false——BM25 分非余弦口径，未重排时
        // 不得凭它过 cosine 置信度终闸；被远程重排裁决后凭 relevance 入上下文）
        List<RagFragment> ranked = bm25.score(query, all); // BM25 全语料打分 + 候选序（含时效衰减）
        ranked.sort(java.util.Comparator.comparingDouble(RagFragment::score).reversed());
        if (topK >= 0 && ranked.size() > topK) {
            return new ArrayList<>(ranked.subList(0, topK));
        }
        return ranked;
    }
}
