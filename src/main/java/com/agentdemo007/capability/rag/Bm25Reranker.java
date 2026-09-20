package com.agentdemo007.capability.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * BM25-lite 重排器（第四层·dev 确定性重排 + prod 主备全失败兜底）。
 *
 * <p>在召回候选集上以 BM25-lite 打分：IDF（稀有词高权重）+ tf 饱和（k1）+ 长度归一（b）。
 * 纯余弦召回会被"词频锤击"误导（某片段反复重复某词即获高余弦）；BM25 因 IDF 与饱和项抑制该现象，
 * 使含稀有查询词的片段上浮。分词复用 {@link HashEmbeddingService#tokenize} 保证 dev 链路口径一致。
 *
 * <p><b>双角色（分数口径纪律）</b>：
 * <ul>
 *   <li>{@link #rerank}——重排位：<b>只改顺序，不覆写任何字段</b>（score/cosineScored/relevance 原样保留）。
 *       本地 BM25 分数与 cosine 不同口径，若覆写 score 会洗掉检索置信度证据，令置信度终闸失明
 *       （真实 RAG 硬约束：低置信知识绝不进 LLM）。</li>
 *   <li>{@link #score}——检索位（{@link Bm25Retriever} 稀疏召回用）：产出 BM25 打分副本
 *       （cosineScored=false——BM25 分非余弦口径，未重排时不得凭它过 cosine 终闸）。</li>
 * </ul>
 *
 * <p>双重角色：① dev（{@code reranker.enabled=false}/缺省）的主重排器；② prod 真模型重排
 * （{@code SiliconFlowReranker}）经 {@code FailoverReranker} 主备全失败后的降级兜底——
 * 重排只改顺序，回退 BM25 严格优于抛异常阻塞（与 embedding 不同：embedding 不可回退 Hash，
 * 因索引真向量与查询 hash 向量空间不一致；重排无此约束）。
 *
 * <p>无信号时（空查询/空分词）保留召回序，不破坏既有顺序。
 */
public class Bm25Reranker implements Reranker {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    /** Phase 20 时效衰减系数：历史片段 BM25 分乘以此值（同等相关性近期优先，§5.4.1）。 */
    private static final double TEMPORAL_DECAY = 0.5;
    private static final String HISTORICAL_TAG = "HISTORICAL";

    /**
     * 在候选集上重排（<b>只排序</b>：返回原候选对象按 BM25 降序排列，字段零覆写）。
     *
     * @param query     用户查询（空/空白 → 保留召回序）
     * @param candidates 召回候选片段（按余弦降序）
     * @return 按 BM25 降序的原候选（同分稳定保留召回序）；空候选返回空
     */
    @Override
    public List<RagFragment> rerank(String query, List<RagFragment> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return new ArrayList<>(candidates);
        }
        List<String> queryTerms = HashEmbeddingService.tokenize(query);
        if (queryTerms.isEmpty()) {
            return new ArrayList<>(candidates);
        }
        List<Double> scores = bm25Scores(queryTerms, candidates);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(scores::get).reversed()); // 稳定降序
        return order.stream().map(candidates::get).collect(Collectors.toList());
    }

    /**
     * 检索位打分（{@link Bm25Retriever} 稀疏召回专用）：返回 BM25 打分副本（候选序）。
     * 副本 {@code score}=BM25 分（含历史片段时效衰减）、{@code cosineScored}=false——
     * BM25 分非余弦口径，未重排时不得凭它过 cosine 置信度终闸。
     */
    public List<RagFragment> score(String query, List<RagFragment> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return new ArrayList<>(candidates);
        }
        List<String> queryTerms = HashEmbeddingService.tokenize(query);
        if (queryTerms.isEmpty()) {
            return new ArrayList<>(candidates);
        }
        List<Double> scores = bm25Scores(queryTerms, candidates);
        List<RagFragment> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            RagFragment f = candidates.get(i);
            scored.add(new RagFragment(f.text(), scores.get(i), f.source(),
                    f.timestamp(), f.validUntil(), f.temporalTag(),
                    f.domain(), f.relevance(), false));
        }
        return scored;
    }

    /** BM25 打分（候选序，含历史片段时效衰减）。 */
    private List<Double> bm25Scores(List<String> queryTerms, List<RagFragment> candidates) {
        int n = candidates.size();
        List<List<String>> docTokens = new ArrayList<>();
        double totalLen = 0;
        for (RagFragment f : candidates) {
            List<String> toks = HashEmbeddingService.tokenize(f.text());
            docTokens.add(toks);
            totalLen += toks.size();
        }
        double avgdl = totalLen / n;
        if (avgdl == 0) {
            avgdl = 1; // 全空文档兜底，避免除零
        }

        List<String> uniqueQuery = queryTerms.stream().distinct().toList();
        Map<String, Integer> df = documentFrequency(uniqueQuery, docTokens);

        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double score = bm25(docTokens.get(i), uniqueQuery, df, n, avgdl);
            // Phase 20 时效衰减：历史片段降权，同等相关性近期优先（过时不冒充当前，§5.4.1）
            if (HISTORICAL_TAG.equals(candidates.get(i).temporalTag())) {
                score *= TEMPORAL_DECAY;
            }
            scores.add(score);
        }
        return scores;
    }

    private static Map<String, Integer> documentFrequency(List<String> queryTerms, List<List<String>> docTokens) {
        Map<String, Integer> df = new HashMap<>();
        for (String term : queryTerms) {
            int count = 0;
            for (List<String> toks : docTokens) {
                if (toks.contains(term)) {
                    count++;
                }
            }
            df.put(term, count);
        }
        return df;
    }

    private static double bm25(List<String> docTokens, List<String> queryTerms,
                              Map<String, Integer> df, int n, double avgdl) {
        Map<String, Long> tf = docTokens.stream()
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));
        double dl = docTokens.size();
        double score = 0;
        for (String term : queryTerms) {
            long f = tf.getOrDefault(term, 0L);
            if (f == 0) {
                continue;
            }
            int d = df.getOrDefault(term, 0);
            double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
            double denom = f + K1 * (1 - B + B * dl / avgdl);
            score += idf * (f * (K1 + 1)) / denom;
        }
        return score;
    }
}
