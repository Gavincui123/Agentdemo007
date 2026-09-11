package com.agentdemo007.intent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图分类校验测试（第三层·分类校验，§5.3.2）。
 *
 * <p>{@link IntentClassifier} 按置信度阈值判定是否放行；不达标视为未定论，
 * 由 {@code IntentRecognitionStep} 兜底为 {@link Intent#OTHER} + 降级继续（§5.12 意图识别行）。
 */
class IntentClassifierTest {

    private final IntentClassifier classifier = new IntentClassifier(0.6);

    @Test
    void passes_highConfidence() {
        assertThat(classifier.passes(new IntentCategory(Intent.REASONING, 0.9))).isTrue();
    }

    @Test
    void fails_lowConfidence() {
        assertThat(classifier.passes(new IntentCategory(Intent.OTHER, 0.0))).isFalse();
    }

    @Test
    void boundary_thresholdInclusive() {
        assertThat(classifier.passes(new IntentCategory(Intent.CHIT_CHAT, 0.6))).isTrue();
    }
}
