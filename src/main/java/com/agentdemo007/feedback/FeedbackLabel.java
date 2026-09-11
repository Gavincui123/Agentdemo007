package com.agentdemo007.feedback;

/**
 * 反馈标签（Phase 15·微调闭环的训练信号）。
 *
 * <p>{@link FeedbackCollector} 收集的在线反馈经此标签区分样本质量：
 * {@link #POSITIVE} 表示该 (prompt, reply) 对是良好示例（可作为正样本）；
 * {@link #NEGATIVE} 表示不佳（可作为负样本或剔除）。具体筛选策略由 T68 {@code FineTuningPipeline} 决定。
 */
public enum FeedbackLabel {
    POSITIVE,
    NEGATIVE
}
