package com.agentdemo007.capability.rag.chroma;

import com.agentdemo007.capability.rag.HybridRetriever;
import com.agentdemo007.capability.rag.RagFragment;
import com.agentdemo007.capability.rag.Retriever;
import com.agentdemo007.capability.rag.VectorRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 真库 Hybrid 检索器（第四层·真实 RAG 主检索通道，{@code vectorstore.type=chroma} 时装配为
 * {@code @Primary} {@link Retriever}，替换 dev {@link HybridRetriever}）。
 *
 * <p><b>宽召回</b>：稠密（{@link VectorRetriever}→Chroma 余弦，{@code recall-dense} 条，人人带
 * cosine 置信度）+ 稀疏（{@code LuceneBm25Retriever}→Lucene 磁盘倒排 BM25，{@code recall-sparse} 条，
 * 生产级不占堆内存）→ 合并去重、BM25 命中优先置顶（精确词/订单号提权）、同文本取稠密副本（余弦口径）。
 * 检索端<b>不截断、不做置信度预筛</b>——候选池交上层走「粗滤→重排（条件跳过）→置信度终闸」
 * 完整漏斗（弱信号不得替强信号淘汰；0.2~0.4 边际带留给 cross-encoder）。
 *
 * <p>{@code topK} 参数语义 = 稠密召回预算（RagStep 传 {@code app.rag.recall-dense}），
 * 非 5.x 时代的注入上限——注入上限由重排 top_n 承担。
 *
 * <p><b>降级</b>：稠密通道异常（嵌入/Chroma 主备全挂）→ 捕获降级稀疏-only（Lucene 磁盘索引本地恒可用，
 * ②每步降级）；稀疏异常上浮（RagStep 捕获 → RAG_SKIP）。
 */
public class ChromaHybridRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(ChromaHybridRetriever.class);

    private final VectorRetriever denseChannel;
    private final Retriever sparseChannel;
    private final int recallSparse;

    public ChromaHybridRetriever(VectorRetriever denseChannel, Retriever sparseChannel,
                                 int recallSparse) {
        this.denseChannel = denseChannel;
        this.sparseChannel = sparseChannel;
        this.recallSparse = recallSparse;
    }

    @Override
    public List<RagFragment> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<RagFragment> dense;
        try {
            dense = denseChannel.retrieve(query, topK); // topK = 稠密召回预算（宽召回，不截注入）
        } catch (Exception e) {
            log.warn("稠密通道失败，降级稀疏-only 检索：reason={}", e.getMessage());
            dense = List.of();
        }
        List<RagFragment> sparse = sparseChannel.retrieve(query, recallSparse);
        // 融合不截断：候选池完整交上层（粗滤/重排/终闸在 RagStep）；BM25-only 候选带词面分
        // （cosineScored=false），由重排裁决或被终闸丢弃——低置信知识绝不进 LLM
        return HybridRetriever.fuseChannels(dense, sparse, -1);
    }
}
