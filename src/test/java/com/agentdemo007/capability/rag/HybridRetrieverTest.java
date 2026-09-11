package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hybrid 检索器测评（Phase 21·稠密+稀疏双 Retriever 融合 + 稠密降级）。
 *
 * <p>{@link HybridRetriever} 融合稠密通道（语义余弦）+ 稀疏通道（BM25 词面）：稀疏命中优先置顶、
 * 稠密补齐、按文本去重。稠密通道异常→降级稀疏-only 继续（混合检索韧性，不阻塞、不回退 Hash）。
 * 用桩 Retriever 通道隔离真实嵌入/BM25，锁融合 + 降级契约。
 */
class HybridRetrieverTest {

    @Test
    void sparsePrioritized_denseFillsIn_deduped() {
        Retriever dense = (q, k) -> List.of(
                new RagFragment("语义近似退款说明", 0.9, "dense"),
                new RagFragment("共享片段", 0.5, "dense"));
        Retriever sparse = (q, k) -> List.of(
                new RagFragment("共享片段", 1.0, "sparse"),
                new RagFragment("精确词ORD123命中", 0.8, "sparse"));
        HybridRetriever hybrid = new HybridRetriever(dense, sparse);

        List<RagFragment> out = hybrid.retrieve("退款 ORD123", 5);

        // 稀疏优先：共享片段取 sparse 版（置顶），精确词次之；稠密补齐语义近似片段
        assertThat(out).extracting(RagFragment::text)
                .containsExactly("共享片段", "精确词ORD123命中", "语义近似退款说明");
        assertThat(out.get(0).source()).isEqualTo("sparse"); // 稀疏版优先
    }

    @Test
    void denseFails_degradesToSparseOnly_neverThrows() {
        Retriever throwingDense = (q, k) -> { throw new RuntimeException("embedding down"); };
        Retriever sparse = (q, k) -> List.of(new RagFragment("稀疏兜底片段", 1.0, "sparse"));
        HybridRetriever hybrid = new HybridRetriever(throwingDense, sparse);

        List<RagFragment> out = hybrid.retrieve("退款", 5);

        assertThat(out).extracting(RagFragment::text).containsExactly("稀疏兜底片段");
    }

    @Test
    void denseMissed_sparseSurfaces_candidate() {
        Retriever dense = (q, k) -> List.of(new RagFragment("无关片段", 0.9, "dense"));
        Retriever sparse = (q, k) -> List.of(new RagFragment("订单SO20240001详情", 1.0, "sparse"));
        HybridRetriever hybrid = new HybridRetriever(dense, sparse);

        List<RagFragment> out = hybrid.retrieve("查 SO20240001", 5);

        assertThat(out.get(0).text()).isEqualTo("订单SO20240001详情"); // 稀疏兜底置顶
        assertThat(out).extracting(RagFragment::text).contains("无关片段"); // 稠密补齐
    }

    @Test
    void topKTruncates_sparseFirst() {
        Retriever dense = (q, k) -> List.of(new RagFragment("d1", 0.9, "dense"));
        Retriever sparse = (q, k) -> List.of(
                new RagFragment("s1", 1.0, "sparse"),
                new RagFragment("s2", 0.8, "sparse"),
                new RagFragment("s3", 0.7, "sparse"));
        HybridRetriever hybrid = new HybridRetriever(dense, sparse);

        List<RagFragment> out = hybrid.retrieve("q", 2);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).text()).isEqualTo("s1"); // 稀疏优先
    }

    @Test
    void emptyQuery_returnsEmpty() {
        HybridRetriever hybrid = new HybridRetriever((q, k) -> List.of(), (q, k) -> List.of());
        assertThat(hybrid.retrieve("", 5)).isEmpty();
        assertThat(hybrid.retrieve(null, 5)).isEmpty();
    }

    @Test
    void nullChannels_degradeToEmpty() {
        HybridRetriever hybrid = new HybridRetriever(null, null);
        assertThat(hybrid.retrieve("退款", 5)).isEmpty();
    }
}
