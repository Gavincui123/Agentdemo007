package com.agentdemo007.intent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图分类测试（第三层·意图识别的内部结果契约）。
 *
 * <p>{@link IntentCategory} 承载识别出的 {@link Intent} 与置信度；对外（PipelineContext/路由）
 * 仅暴露意图枚举，不外泄内部置信度（§5.3.4 仅返回意图枚举）。
 */
class IntentCategoryTest {

    @Test
    void holdsIntentAndConfidence() {
        IntentCategory c = new IntentCategory(Intent.REASONING, 0.92);
        assertThat(c.intent()).isEqualTo(Intent.REASONING);
        assertThat(c.confidence()).isEqualTo(0.92);
    }

    @Test
    void equalsByValue() {
        assertThat(new IntentCategory(Intent.CHIT_CHAT, 0.8))
                .isEqualTo(new IntentCategory(Intent.CHIT_CHAT, 0.8));
    }

    @Test
    void unknownFallback() {
        IntentCategory c = IntentCategory.unknown();
        assertThat(c.intent()).isEqualTo(Intent.OTHER);
        assertThat(c.confidence()).isZero();
    }
}
