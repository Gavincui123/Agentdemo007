package com.agentdemo007.capability.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 内存向量库（第四层·dev 后端，余弦 Top-K，Phase 20 起兼 {@link KeywordIndex} 精确通道）。
 *
 * <p>线程安全（synchronized）：{@link #index} 经 {@link EmbeddingService} 嵌入并存储；
 * {@link #search} 遍历计算余弦相似度，降序取 Top-K。零范数查询返回空。prod 覆盖为 pgvector/Redis。
 *
 * <p>Phase 20：兼任 {@link KeywordIndex}（同语料子串精确匹配）——dev 单库双用避免额外索引；
 * prod 拆为独立 BM25/ES 关键词索引（{@code VectorStore} 不实现 {@code KeywordIndex} 时，
 * 装配 {@link KeywordIndex#NO_OP} 回退纯向量，②降级）。
 */
public class InMemoryVectorStore implements VectorStore, KeywordIndex, RagCorpus, SourceDeletableStore {

    private final EmbeddingService embedding;
    private final List<Entry> entries = new ArrayList<>();

    public InMemoryVectorStore(EmbeddingService embedding) {
        this.embedding = embedding;
    }

    @Override
    public synchronized void index(List<RagFragment> fragments) {
        if (fragments == null) {
            return;
        }
        for (RagFragment f : fragments) {
            entries.add(new Entry(f, embedding.embed(f.text())));
        }
    }

    @Override
    public synchronized List<RagFragment> search(float[] query, int topK) {
        if (query == null || entries.isEmpty()) {
            return List.of();
        }
        double queryNorm = norm(query);
        if (queryNorm == 0) {
            return List.of(); // 无效查询
        }
        List<RagFragment> scored = new ArrayList<>();
        for (Entry e : entries) {
            double sim = cosine(query, e.vector, queryNorm);
            // 余弦口径（cosineScored=true）：sim 可过 cosine 置信度终闸；保留真库扩展字段
            scored.add(new RagFragment(e.fragment.text(), sim, e.fragment.source(),
                    e.fragment.timestamp(), e.fragment.validUntil(), e.fragment.temporalTag(),
                    e.fragment.domain(), e.fragment.relevance(), true));
        }
        scored.sort(Comparator.comparingDouble(RagFragment::score).reversed());
        if (topK >= 0 && scored.size() > topK) {
            return new ArrayList<>(scored.subList(0, topK));
        }
        return scored;
    }

    /**
     * Phase 20 关键词精确通道：按精确词（订单号/型号/发票类型）子串匹配召回。
     *
     * <p>分数 = 命中关键词数（精确匹配强度）。补向量余弦在精确标识符上的漂移——
     * 订单号语义模糊但词面精确，子串命中即纳入候选（由 Hybrid 融合 + Reranker BM25 高 IDF 上浮）。
     */
    @Override
    public synchronized List<RagFragment> searchByKeywords(List<String> keywords, int topK) {
        if (keywords == null || keywords.isEmpty() || entries.isEmpty()) {
            return List.of();
        }
        List<RagFragment> scored = new ArrayList<>();
        for (Entry e : entries) {
            String text = e.fragment.text();
            if (text == null) {
                continue;
            }
            String lcText = text.toLowerCase(); // 关键词通道大小写无关（code-review #6）：Ord 与 ORD 互命中
            int matches = 0;
            for (String kw : keywords) {
                if (kw != null && !kw.isEmpty() && lcText.contains(kw.toLowerCase())) {
                    matches++;
                }
            }
            if (matches > 0) {
                // 命中数非余弦口径（cosineScored=false）：未重排时不得凭它过 cosine 终闸
                scored.add(new RagFragment(text, matches, e.fragment.source(),
                        e.fragment.timestamp(), e.fragment.validUntil(), e.fragment.temporalTag(),
                        e.fragment.domain(), e.fragment.relevance(), false));
            }
        }
        scored.sort(Comparator.comparingDouble(RagFragment::score).reversed());
        if (topK >= 0 && scored.size() > topK) {
            return new ArrayList<>(scored.subList(0, topK));
        }
        return scored;
    }

    /** Phase-21 稀疏检索：暴露全部已索引片段供 {@link Bm25Retriever} 全语料 BM25 召回（dev 单库双用；prod 拆独立 ES 索引）。 */
    @Override
    public synchronized List<RagFragment> fragments() {
        return entries.stream().map(Entry::fragment).toList();
    }

    /**
     * 按精确 source 删除（[[kb-ingest-design]] 知识库换版/下架配套）：匹配片段移出稠密+关键词
     * 通道（同一份 entries），BM25 全语料经 {@link #fragments()} 同步收敛。
     */
    @Override
    public synchronized int deleteBySources(java.util.Collection<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return 0;
        }
        java.util.Set<String> set = new java.util.HashSet<>(sources);
        int before = entries.size();
        entries.removeIf(e -> set.contains(e.fragment().source()));
        return before - entries.size();
    }

    private static double cosine(float[] query, float[] vec, double queryNorm) {
        double dot = 0;
        double vn = 0;
        for (int i = 0; i < query.length; i++) {
            dot += query[i] * vec[i];
            vn += vec[i] * vec[i];
        }
        double denom = queryNorm * Math.sqrt(vn);
        return denom == 0 ? 0 : dot / denom;
    }

    private static double norm(float[] v) {
        double n = 0;
        for (float f : v) {
            n += f * f;
        }
        return Math.sqrt(n);
    }

    private record Entry(RagFragment fragment, float[] vector) {
    }
}
