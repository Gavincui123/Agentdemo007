package com.agentdemo007.capability.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Hybrid 检索器（第四层·Phase 21·稠密+稀疏双 Retriever 通道融合）。
 *
 * <p>融合稠密通道（{@link VectorRetriever}→{@link EmbeddingService}→余弦语义召回）与稀疏通道
 * （{@link Bm25Retriever}→BM25 全语料词面召回）：稀疏(BM25)命中优先置顶（稀有词/精确标识符高 IDF，
 * 与旧"精确词置顶"精神一致）、稠密补齐、按文本去重。两通道互补：稠密捕语义近似、稀疏捕词面精确。
 *
 * <p><b>降级（混合检索韧性）</b>：稠密通道异常（SiliconFlow embedding 主备全挂）→捕获、降级稀疏-only
 * 继续（不回退 HashEmbedding——索引真向量与查询 hash 向量空间不一致，余弦无意义；稀疏兜底是混合检索
 * 的韧性所在，②每步降级不阻塞）。稀疏通道异常则上浮（{@link RagStep} 捕获→RAG_SKIP）——稀疏是本地
 * BM25，异常属配置/语料问题，跳过更诚实。
 */
public class HybridRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final Retriever denseChannel;
    private final Retriever sparseChannel;

    public HybridRetriever(Retriever denseChannel, Retriever sparseChannel) {
        this.denseChannel = (denseChannel != null) ? denseChannel : (q, k) -> List.of();
        this.sparseChannel = (sparseChannel != null) ? sparseChannel : (q, k) -> List.of();
    }

    @Override
    public List<RagFragment> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<RagFragment> dense;
        try {
            dense = denseChannel.retrieve(query, topK);
        } catch (Exception e) {
            // 稠密通道失败（embedding 主备全挂）→降级稀疏-only 继续（混合检索韧性，不阻塞、不回退 Hash）
            log.warn("稠密通道失败，降级稀疏-only 检索：reason={}", e.getMessage());
            dense = List.of();
        }
        List<RagFragment> sparse = sparseChannel.retrieve(query, topK);
        return fuse(dense, sparse, topK);
    }

    /**
     * 双通道融合：稀疏(BM25)命中优先置顶，稠密补齐，按文本去重。
     */
    private List<RagFragment> fuse(List<RagFragment> dense, List<RagFragment> sparse, int topK) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<RagFragment> fused = new ArrayList<>();
        for (RagFragment f : sparse) {       // 稀疏优先（精确词/稀有词高 IDF）
            if (seen.add(textKey(f))) {
                fused.add(f);
            }
        }
        for (RagFragment f : dense) {        // 稠密补齐
            if (seen.add(textKey(f))) {
                fused.add(f);
            }
        }
        if (topK >= 0 && fused.size() > topK) {
            return new ArrayList<>(fused.subList(0, topK));
        }
        return fused;
    }

    private static String textKey(RagFragment f) {
        return (f != null && f.text() != null) ? f.text() : "";
    }
}
