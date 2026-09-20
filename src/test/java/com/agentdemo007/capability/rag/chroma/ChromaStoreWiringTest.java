package com.agentdemo007.capability.rag.chroma;

import com.agentdemo007.capability.rag.HybridRetriever;
import com.agentdemo007.capability.rag.RagSeedRunner;
import com.agentdemo007.capability.rag.Retriever;
import com.agentdemo007.capability.rag.RetrievalValidator;
import com.agentdemo007.capability.rag.InMemoryVectorStore;
import com.agentdemo007.capability.rag.VectorStoreConfig;
import com.agentdemo007.capability.rag.lucene.LuceneBm25IndexService;
import com.agentdemo007.capability.rag.lucene.LuceneBm25Retriever;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真库装配门控测试（属性门控互斥：{@code vectorstore.type}=chroma|inmemory，项目惯例——
 * 非 bean 存在性竞态）。风格对齐 {@code RoutePlanWiringIntegrationTest}（noop 门控锁定）。
 *
 * <p>chroma 模式：{@code ChromaVectorStore}（稠密）+ {@code LuceneBm25IndexService}/{@code LuceneBm25Retriever}
 * （稀疏，磁盘倒排）装配 + in-memory/种子退让（种子数字与真语料冲突且污染确定性 id 集合）；
 * 默认（inmemory）：{@code InMemoryVectorStore} + 种子语料，chroma bean 全缺。
 * lucene-dir 指向 target 测试目录（不污染项目根）。
 */
class ChromaStoreWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(VectorStoreConfig.class, ChromaStoreConfig.class, RagSeedRunner.class)
            .withPropertyValues("app.rag.lucene-dir=target/test-lucene-wiring");

    @Test
    void chromaMode_chromaAndLuceneBeansTakeOver_inMemoryAndSeedRetreat() {
        runner.withPropertyValues("vectorstore.type=chroma").run(ctx -> {
            assertThat(ctx).hasSingleBean(ChromaVectorStore.class);
            assertThat(ctx).hasSingleBean(LuceneBm25IndexService.class);
            assertThat(ctx).hasSingleBean(LuceneBm25Retriever.class);
            assertThat(ctx).hasSingleBean(ChromaRestClient.class);
            assertThat(ctx).hasSingleBean(RetrievalValidator.class);
            // @Primary 主检索器 = 真库融合
            assertThat(ctx.getBean(Retriever.class)).isInstanceOf(ChromaHybridRetriever.class);
            // 属性门控互斥：in-memory/种子退让
            assertThat(ctx).doesNotHaveBean(InMemoryVectorStore.class);
            assertThat(ctx).doesNotHaveBean(RagSeedRunner.class);
        });
    }

    @Test
    void defaultMode_inMemoryBeans_seedEnabled_chromaAbsent() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(InMemoryVectorStore.class);
            assertThat(ctx).hasSingleBean(RagSeedRunner.class);
            assertThat(ctx.getBean(Retriever.class)).isInstanceOf(HybridRetriever.class);
            assertThat(ctx).doesNotHaveBean(ChromaVectorStore.class);
            assertThat(ctx).doesNotHaveBean(ChromaRestClient.class);
            assertThat(ctx).doesNotHaveBean(LuceneBm25IndexService.class);
        });
    }
}
