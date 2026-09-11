package com.agentdemo007.capability.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code reranker.*} 配置绑定（稀疏重排主备容灾·Phase 21）。
 *
 * <p>Nacos 的 {@code reranker} 段经 Spring relaxed binding 绑定到此 POJO，由 {@link RerankerConfig}
 * 翻成 {@link SiliconFlowReranker}（每 provider 一个）+ {@link FailoverReranker}（主备，全失败→降级 BM25）。
 * 结构对齐实际 dataId：
 * <pre>
 * reranker:
 *   enabled: true
 *   providers:
 *     - id: siliconflow
 *       base-url: https://api.siliconflow.cn/v1
 *       api-key: ${SF_KEY}
 *       model: Qwen/Qwen3-Reranker-8B
 * </pre>
 * {@code providers} 首位=主，其余=备。密钥应经环境变量注入，不落明文。
 */
@ConfigurationProperties(prefix = "reranker")
public class RerankerProperties {

    private boolean enabled = false;
    private List<Provider> providers = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<Provider> getProviders() { return providers; }
    public void setProviders(List<Provider> providers) {
        this.providers = providers == null ? new ArrayList<>() : providers;
    }

    /** 单服务商：标识 + 端点 + 密钥 + 重排模型 API 名。 */
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
