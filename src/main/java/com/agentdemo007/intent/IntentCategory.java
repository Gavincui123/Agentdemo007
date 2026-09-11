package com.agentdemo007.intent;

/**
 * 意图分类结果（第三层·意图识别的内部契约）。
 *
 * <p>承载识别出的 {@link Intent} 与置信度。对外（{@code PipelineContext.intent} / 路由分发）
 * 仅暴露意图枚举，不外泄内部置信度（§5.3.4 对外仅返回意图枚举）；置信度供
 * {@code IntentClassifier} 分类校验与升级判定（§5.3.2）。
 *
 * @param intent     识别出的意图（{@link Intent#OTHER} 为兜底）
 * @param confidence 置信度 {@code [0,1]}，规则命中通常 {@code >=0.85}，兜底 {@code 0}
 */
public record IntentCategory(Intent intent, double confidence) {

    /** 兜底未知分类（模型不可用时回退）。 */
    public static IntentCategory unknown() {
        return new IntentCategory(Intent.OTHER, 0.0);
    }
}
