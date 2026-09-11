package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关键词通道大小写无关测评（code-review #6）。
 *
 * <p>dev {@link InMemoryVectorStore#searchByKeywords} 原 {@code text.contains(kw)} 大小写敏感，
 * 种子 {@code Ord} 与关键词 {@code ORD} 不匹配→零召回。关键词通道须大小写无关命中（向量通道
 * 哈希嵌入仍大小写敏感属 dev 限制，但精确词主要走关键词通道，规范化后可命中）。
 */
class InMemoryVectorStoreCaseTest {

    private final HashEmbeddingService embedding = new HashEmbeddingService();

    @Test
    void searchByKeywords_caseInsensitive_matchesMixedCaseSeed() {
        InMemoryVectorStore store = new InMemoryVectorStore(embedding);
        store.index(List.of(new RagFragment("订单Ord123456详情", 0.0, "doc")));

        List<RagFragment> results = store.searchByKeywords(List.of("ORD123456"), 5);

        assertThat(results).as("关键词通道应大小写无关命中混合大小写种子").hasSize(1);
        assertThat(results.get(0).text()).isEqualTo("订单Ord123456详情");
    }
}
