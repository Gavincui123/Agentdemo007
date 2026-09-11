package com.agentdemo007.capability.rag;

import java.util.List;

/**
 * 嵌入主备容灾（第四层·稠密通道韧性）。
 *
 * <p>持有有序 provider 列表（主在前），{@code embed} 逐 provider 尝试：主成功即返回，主失败→切备；
 * 主备<b>全失败→抛异常</b>（由 {@link HybridRetriever} 捕获、降级稀疏-only 继续——不回退 HashEmbedding：
 * 索引真向量与查询 hash 向量空间不一致，余弦无意义；稀疏兜底是混合检索的韧性所在，②每步降级不阻塞）。
 *
 * <p>空文本返回零向量（不调任何 provider；契约同单 provider，{@link InMemoryVectorStore} 零范数→空召回）。
 */
public class FailoverEmbeddingService implements EmbeddingService {

    private final List<EmbeddingService> providers;

    public FailoverEmbeddingService(List<EmbeddingService> providers) {
        this.providers = providers == null ? List.of() : providers;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        RuntimeException last = new RuntimeException("无嵌入 provider 配置（embedding.providers 为空）");
        for (EmbeddingService p : providers) {
            try {
                return p.embed(text);
            } catch (RuntimeException e) {
                last = e; // 主失败→切备
            }
        }
        throw last; // 主备全失败→抛（HybridRetriever 捕获降级稀疏-only）
    }
}
