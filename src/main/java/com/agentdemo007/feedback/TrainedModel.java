package com.agentdemo007.feedback;

import java.util.Set;

/**
 * 训练产出的新模型描述（Phase 15·T68 微调闭环·训练输出 → 注册输入）。
 *
 * <p>由 {@code TrainingJobRunner}（外部训练 seam）产出，{@link ModelRegistrar} 据此构建
 * {@link com.agentdemo007.gateway.config.ModelMetadata} 注册到 {@link ModelConfigCenter}。
 *
 * @param modelId  新模型标识（唯一）
 * @param provider 提供方
 * @param endpoint 端点 URL
 * @param tags     能力标签（路由选择输入，对齐 {@code RouteRule.RouteType} 名）
 * @param weight   初始权重
 */
public record TrainedModel(String modelId, String provider, String endpoint,
                           Set<String> tags, int weight) {
}
