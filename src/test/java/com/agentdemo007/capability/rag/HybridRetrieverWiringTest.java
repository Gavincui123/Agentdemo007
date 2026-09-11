package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hybrid 检索器 Spring 装配测评（Phase 20·T89 wiring）。
 *
 * <p>验 {@code VectorStoreConfig} 装配 {@link HybridRetriever} 为 {@link Retriever} 主 bean
 * （{@code @Primary}），{@code RagStep} 经 {@link Retriever} seam 注入之——非纯向量
 * {@link VectorRetriever}。dev 关键词通道由 {@link InMemoryVectorStore} 兼任（同语料精确匹配），
 * prod 无关键词索引时装配 {@link KeywordIndex#NO_OP} 回退纯向量（②降级）。
 *
 * <p>特征测试：锁定主检索器为 Hybrid（防误退为纯向量）；既有 rag.json 测评（无精确词→关键词通道空→
 * 纯向量等价）由 GoldenSuiteTest 守回归。
 */
@SpringBootTest
class HybridRetrieverWiringTest {

    @Autowired
    private Retriever retriever;

    @Test
    void primaryRetriever_isHybrid() {
        assertThat(retriever).isInstanceOf(HybridRetriever.class);
    }
}
