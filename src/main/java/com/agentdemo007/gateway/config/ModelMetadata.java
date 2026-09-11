package com.agentdemo007.gateway.config;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 模型元数据（第六层·模型配置中心的数据真相源）。
 *
 * <p>定义一个可注册模型的静态属性：标识、提供方、端点、密钥、能力标签、权重、成本、可用性。
 * 标签/权重/成本是三种选择策略（Tag/Weight/Cost）的输入；状态驱动是否参与路由。
 * 收口：所有模型选择逻辑只读此元数据，不自造描述结构。
 */
public final class ModelMetadata {

    public enum ModelStatus { ENABLED, DISABLED }

    private final String id;
    private final String name;
    private final String provider;
    private final String endpoint;
    private final String apiKey;
    private final int maxTokens;
    private final Duration timeout;
    private final Set<String> tags;
    private final int weight;
    private final double costPer1KTokens;
    private final ModelStatus status;

    private ModelMetadata(String id, String name, String provider, String endpoint,
                          String apiKey, int maxTokens, Duration timeout,
                          Set<String> tags, int weight, double costPer1KTokens, ModelStatus status) {
        this.id = Objects.requireNonNull(id, "模型 id 不能为空");
        this.name = name;
        this.provider = provider;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.tags = tags;
        this.weight = weight;
        this.costPer1KTokens = costPer1KTokens;
        this.status = status;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String provider() { return provider; }
    public String endpoint() { return endpoint; }
    public String apiKey() { return apiKey; }
    public int maxTokens() { return maxTokens; }
    public Duration timeout() { return timeout; }
    public Set<String> tags() { return tags; }
    public int weight() { return weight; }
    public double costPer1KTokens() { return costPer1KTokens; }
    public ModelStatus status() { return status; }

    public boolean isEnabled() { return status == ModelStatus.ENABLED; }

    /**
     * 返回仅替换权重的新副本（其他字段原样保留）。
     *
     * <p>用于运维控制台动态调权（Phase 15·{@code RouteWeightController} 热生效路径）：
     * {@link ModelConfigCenter#applyWeight} 经此重建后重新注册到 {@link ModelRegistry}，
     * 使新权重对 {@code WeightBasedSelector} 立即可见（不可变模型 → 整体替换而非原地改）。
     */
    public ModelMetadata withWeight(int newWeight) {
        return new ModelMetadata(id, name, provider, endpoint, apiKey,
                maxTokens, timeout, tags, newWeight, costPer1KTokens, status);
    }

    public static Builder builder(String id) { return new Builder(id); }

    public static final class Builder {
        private final String id;
        private String name;
        private String provider;
        private String endpoint;
        private String apiKey;
        private int maxTokens;
        private Duration timeout;
        private Set<String> tags = new LinkedHashSet<>();
        private int weight = 1;
        private double costPer1KTokens = 0.0;
        private ModelStatus status = ModelStatus.ENABLED;

        public Builder(String id) {
            this.id = id;
            this.name = id;
            this.provider = id.contains(":") ? id.substring(0, id.indexOf(':')) : id;
        }

        public Builder name(String name) { this.name = name; return this; }
        public Builder provider(String provider) { this.provider = provider; return this; }
        public Builder endpoint(String endpoint) { this.endpoint = endpoint; return this; }
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }
        public Builder tags(Set<String> tags) { this.tags = new LinkedHashSet<>(tags); return this; }
        public Builder weight(int weight) { this.weight = weight; return this; }
        public Builder costPer1KTokens(double costPer1KTokens) { this.costPer1KTokens = costPer1KTokens; return this; }
        public Builder status(ModelStatus status) { this.status = status; return this; }
        public ModelMetadata build() {
            return new ModelMetadata(id, name, provider, endpoint, apiKey,
                    maxTokens, timeout, tags, weight, costPer1KTokens, status);
        }
    }
}
