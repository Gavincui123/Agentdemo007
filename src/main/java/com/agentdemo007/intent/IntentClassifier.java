package com.agentdemo007.intent;

/**
 * 意图分类校验（第三层·分类校验，§5.3.2）。
 *
 * <p>按置信度阈值判定识别结果是否放行：达标通过；不达标视为未定论，
 * 由 {@code IntentRecognitionStep} 兜底为 {@link Intent#OTHER} + 降级继续
 * （§5.12 意图识别行：兜底 UNKNOWN，无"短路"字样即继续）。
 *
 * <p>阈值来自配置（Nacos 热更新），Phase 7 先内置默认。
 */
public class IntentClassifier {

    private final double confidenceThreshold;

    public IntentClassifier(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    /** 置信度达标则放行（含等号）。 */
    public boolean passes(IntentCategory category) {
        return category != null && category.confidence() >= confidenceThreshold;
    }

    public double threshold() {
        return confidenceThreshold;
    }
}
