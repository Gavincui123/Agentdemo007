package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存向量库测试（第四层·dev 向量库后端，余弦 Top-K）。
 *
 * <p>无 pgvector/Redis 即可运行：index 嵌入并存储；search 按余弦相似度降序返回 Top-K，
 * 返回片段携带相似度分数。prod 覆盖为 pgvector/Redis vector 桥接（延后，§8 风险表）。
 */
class InMemoryVectorStoreTest {

    private final HashEmbeddingService embedding = new HashEmbeddingService();
    private final InMemoryVectorStore store = new InMemoryVectorStore(embedding);

    private static RagFragment frag(String text) {
        return new RagFragment(text, 0.0, "test");
    }

    @Test
    void searchReturnsMostSimilarFirst_withScore() {
        store.index(List.of(frag("退款 政策 说明"), frag("天气 预报")));
        List<RagFragment> results = store.search(embedding.embed("退款"), 2);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).text()).isEqualTo("退款 政策 说明");
        assertThat(results.get(0).score()).isGreaterThan(0.0);
    }

    @Test
    void topKLimitsResults() {
        store.index(List.of(frag("退款 a"), frag("退款 b"), frag("退款 c"), frag("退款 d")));
        List<RagFragment> results = store.search(embedding.embed("退款"), 2);
        assertThat(results).hasSize(2);
    }

    @Test
    void emptyStore_returnsEmpty() {
        List<RagFragment> results = store.search(embedding.embed("anything"), 3);
        assertThat(results).isEmpty();
    }

    @Test
    void disjointQuery_scoresLowOrAbsent() {
        store.index(List.of(frag("退款 政策")));
        List<RagFragment> results = store.search(embedding.embed("天气 预报"), 1);
        // 无共享 token → 余弦 0；Top-K 仍可能返回但分数为 0
        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void zeroNormQuery_returnsEmpty() {
        store.index(List.of(frag("退款 政策")));
        List<RagFragment> results = store.search(new float[HashEmbeddingService.DIMENSION], 1);
        assertThat(results).isEmpty();
    }
}
