package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 向量检索器测试（第四层·Top-K 召回入口）。
 *
 * <p>经 Embedding 向量化查询 → VectorStore 余弦 Top-K → 返回按分数降序的片段。
 * 空查询/空库返回空。引擎无关：dev 用 {@link HashEmbeddingService}+{@link InMemoryVectorStore}。
 */
class VectorRetrieverTest {

    private final HashEmbeddingService embedding = new HashEmbeddingService();
    private final InMemoryVectorStore store = new InMemoryVectorStore(embedding);
    private final VectorRetriever retriever = new VectorRetriever(embedding, store);

    private static RagFragment frag(String text) {
        return new RagFragment(text, 0.0, "test");
    }

    @Test
    void retrieveReturnsRankedFragments() {
        store.index(List.of(frag("退款 政策 说明"), frag("天气 预报")));
        List<RagFragment> results = retriever.retrieve("退款", 2);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).text()).isEqualTo("退款 政策 说明");
        assertThat(results.get(0).score()).isGreaterThan(0.0);
    }

    @Test
    void topKLimitsResults() {
        store.index(List.of(frag("退款 a"), frag("退款 b"), frag("退款 c"), frag("退款 d")));
        List<RagFragment> results = retriever.retrieve("退款", 2);
        assertThat(results).hasSize(2);
    }

    @Test
    void emptyQueryReturnsEmpty() {
        store.index(List.of(frag("退款 政策")));
        assertThat(retriever.retrieve("", 3)).isEmpty();
        assertThat(retriever.retrieve(null, 3)).isEmpty();
        assertThat(retriever.retrieve("   ", 3)).isEmpty();
    }

    @Test
    void emptyStoreReturnsEmpty() {
        assertThat(retriever.retrieve("退款", 3)).isEmpty();
    }
}
