package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RagConfigReporter} 单测（混合检索·启动配置可观测）。
 *
 * <p>断言：① dev（props 全 null）→ 显示 dev 占位（HashEmbedding/Bm25Reranker）+ 稀疏本地；
 * ② prod → 显示 provider/model/降级策略 + 脱敏 key 尾4，且不含明文 key 全文；
 * ③ providers 空 → 显示空告警。
 */
class RagConfigReporterTest {

    private static final String KEY = "sk-secret-1234567890abcdef"; // 尾4=cdef

    @Test
    void dev_whenBothNull_showsDevFallbacks() {
        String r = RagConfigReporter.report(null, null);
        assertThat(r).contains("稀疏召回=BM25 本地");
        assertThat(r).contains("dev HashEmbedding");
        assertThat(r).contains("dev Bm25Reranker");
    }

    @Test
    void prod_showsProvidersMaskedKeysAndDegradePolicy() {
        EmbeddingProperties emb = new EmbeddingProperties();
        emb.setEnabled(true);
        emb.setProviders(List.of(embProvider("siliconflow", "https://api.siliconflow.cn/v1", KEY, "Qwen/Qwen3-Embedding-8B")));
        RerankerProperties rer = new RerankerProperties();
        rer.setEnabled(true);
        rer.setProviders(List.of(rerProvider("siliconflow", "https://api.siliconflow.cn/v1", KEY, "Qwen/Qwen3-Reranker-8B")));

        String r = RagConfigReporter.report(emb, rer);

        assertThat(r).contains("真 SiliconFlow 嵌入+主备", "Qwen/Qwen3-Embedding-8B", "尾4=cdef", "降级稀疏-only");
        assertThat(r).contains("真 SiliconFlow 重排+主备", "Qwen/Qwen3-Reranker-8B", "降级 BM25");
        assertThat(r).doesNotContain(KEY); // 不落明文
        assertThat(r).doesNotContain("secret-1234567890");
    }

    @Test
    void prod_emptyProviders_showsEmptyWarning() {
        EmbeddingProperties emb = new EmbeddingProperties();
        emb.setEnabled(true);
        String r = RagConfigReporter.report(emb, null);
        assertThat(r).contains("空——embedding.enabled=true 但 providers 未配置");
    }

    private static EmbeddingProperties.Provider embProvider(String id, String url, String key, String model) {
        EmbeddingProperties.Provider p = new EmbeddingProperties.Provider();
        p.setId(id); p.setBaseUrl(url); p.setApiKey(key); p.setModel(model);
        return p;
    }

    private static RerankerProperties.Provider rerProvider(String id, String url, String key, String model) {
        RerankerProperties.Provider p = new RerankerProperties.Provider();
        p.setId(id); p.setBaseUrl(url); p.setApiKey(key); p.setModel(model);
        return p;
    }
}
