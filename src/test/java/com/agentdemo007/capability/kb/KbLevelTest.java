package com.agentdemo007.capability.kb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbLevel} 词表单测（Phase 21 安全轴）：档位比较、int 收敛、fail-closed 越界兜底。
 */
class KbLevelTest {

    @Test
    void codesAlignWithOrdinalForDbStorage() {
        assertThat(KbLevel.values()).hasSize(6);
        for (KbLevel level : KbLevel.values()) {
            assertThat(level.code()).isEqualTo(level.ordinal());
        }
    }

    @Test
    void atLeastImplementsSubjectLevelPredicate() {
        assertThat(KbLevel.V5.atLeast(KbLevel.V3)).isTrue();
        assertThat(KbLevel.V3.atLeast(KbLevel.V3)).isTrue(); // 等级相等可见
        assertThat(KbLevel.V1.atLeast(KbLevel.V3)).isFalse();
        assertThat(KbLevel.V0.atLeast(KbLevel.V0)).isTrue();
        assertThat(KbLevel.V3.atLeast(null)).isTrue(); // 文档缺档位 → 不设限
    }

    @Test
    void fromCodeClampsOutOfRangeToV0() {
        assertThat(KbLevel.fromCode(3)).isEqualTo(KbLevel.V3);
        assertThat(KbLevel.fromCode(0)).isEqualTo(KbLevel.V0);
        assertThat(KbLevel.fromCode(-1)).isEqualTo(KbLevel.V0); // 坏数据 fail-closed 不放大权限
        assertThat(KbLevel.fromCode(99)).isEqualTo(KbLevel.V0);
    }
}
