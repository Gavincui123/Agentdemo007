package com.agentdemo007.admin;

import java.util.Set;

/**
 * 模型摘要 DTO（Phase 19·管理台对外收口）。
 *
 * <p>{@link com.agentdemo007.gateway.config.ModelMetadata} 的只读投影——<b>刻意剔除 {@code apiKey}</b>
 * （密钥不外泄：管理台只看可路由属性，不见密钥）。{@code status} 映射为字符串稳定标识
 * （{@code ENABLED}/{@code DISABLED}），{@code enabled} 为便捷布尔。权重为热生效后的当前值。
 */
public record ModelSummary(String id, String name, String provider, String endpoint,
                           int weight, String status, boolean enabled,
                           Set<String> tags, int maxTokens, double costPer1KTokens) {
}
