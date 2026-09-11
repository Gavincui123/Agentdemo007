package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 链路时效字段透传测评（Phase 20·T90b/d）。
 *
 * <p>历史片段的 {@code timestamp}/{@code validUntil}/{@code temporalTag} 须经检索→重排全链路透传，
 * 到达 RagStep 抽取边界供 {@link RagFragment#displayText()} 时效标注。既有 3 参构造会丢 temporal，
 * 故 search/searchByKeywords/rerank 须用全参构造保留之——否则历史片段到 RagStep 退化为无标注原文，
 * 过时知识冒充当前（违 §5.4.1 时效治理）。
 */
class RagTemporalPreservationTest {

    private final HashEmbeddingService embedding = new HashEmbeddingService();

    @Test
    void search_preservesTemporalFields() {
        InMemoryVectorStore store = new InMemoryVectorStore(embedding);
        Instant ts = Instant.parse("2024-01-01T00:00:00Z");
        Instant vu = Instant.parse("2024-12-31T00:00:00Z");
        store.index(List.of(new RagFragment("退款政策历史", 0.0, "doc1", ts, vu, "HISTORICAL")));

        List<RagFragment> results = store.search(embedding.embed("退款政策历史"), 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).temporalTag()).isEqualTo("HISTORICAL");
        assertThat(results.get(0).validUntil()).isEqualTo(vu);
        assertThat(results.get(0).timestamp()).isEqualTo(ts);
    }

    @Test
    void searchByKeywords_preservesTemporalFields() {
        InMemoryVectorStore store = new InMemoryVectorStore(embedding);
        Instant vu = Instant.parse("2024-06-30T00:00:00Z");
        store.index(List.of(new RagFragment("订单ORD123456历史", 0.0, "doc1", null, vu, "HISTORICAL")));

        List<RagFragment> results = store.searchByKeywords(List.of("ORD123456"), 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).temporalTag()).isEqualTo("HISTORICAL");
        assertThat(results.get(0).validUntil()).isEqualTo(vu);
    }

    @Test
    void rerank_preservesTemporalFields() {
        Reranker reranker = new Bm25Reranker();
        Instant vu = Instant.parse("2024-06-30T00:00:00Z");
        RagFragment hist = new RagFragment("退款政策", 0.5, "doc1", null, vu, "HISTORICAL");

        List<RagFragment> out = reranker.rerank("退款", List.of(hist));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).temporalTag()).isEqualTo("HISTORICAL");
        assertThat(out.get(0).validUntil()).isEqualTo(vu);
        assertThat(out.get(0).displayText()).startsWith("【历史参考资料·截至2024-06-30】");
    }
}
