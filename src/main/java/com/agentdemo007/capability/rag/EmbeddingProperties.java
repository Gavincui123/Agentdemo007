package com.agentdemo007.capability.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code embedding.*} 配置绑定（稠密向量化主备容灾·Phase 21）。
 *
 * <p>Nacos 的 {@code embedding} 段经 Spring relaxed binding 绑定到此 POJO，由 {@link EmbeddingConfig}
 * 翻成 {@link SiliconFlowEmbeddingService}（每 provider 一个）+ {@link FailoverEmbeddingService}（主备）。
 * 结构对齐实际 dataId：
 * <pre>
 * embedding:
 *   enabled: true
 *   providers:
 *     - id: siliconflow
 *       base-url: https://api.siliconflow.cn/v1
 *       api-key: ${SF_KEY}
 *       model: Qwen/Qwen3-Embedding-8B
 * </pre>
 * {@code providers} 首位=主，其余=备（逐 provider 尝试，主失败→切备；全失败→抛→HybridRetriever 降级稀疏）。
 * 密钥应经环境变量注入（{@code ${...}} 占位），不落明文。
 */
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingProperties {

    private boolean enabled = false;
    private List<Provider> providers = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<Provider> getProviders() { return providers; }
    public void setProviders(List<Provider> providers) {
        this.providers = providers == null ? new ArrayList<>() : providers;
    }

    /** 单服务商：标识 + 端点 + 密钥 + 嵌入模型 API 名。 */
    public static class Provider {
        private String id;
        private String baseUrl;
        private String apiKey;
        private String model;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }
}
