package com.agentdemo007.common.degradation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 降级场景枚举测试：Phase 12 新增 {@link DegradationScenario#OUTPUT_FALLBACK}（§5.12 结构化输出行）。
 */
class DegradationScenarioTest {

    @Test
    void outputFallback_hasNonBlankPhrase() {
        assertThat(DegradationScenario.OUTPUT_FALLBACK.phrase()).isNotBlank();
    }

    @Test
    void outputFallback_resolvableViaValueOf() {
        // eval 数据以场景名（字符串）对照，须可经 name() 往返解析
        DegradationScenario resolved = DegradationScenario.valueOf("OUTPUT_FALLBACK");
        assertThat(resolved).isEqualTo(DegradationScenario.OUTPUT_FALLBACK);
    }
}
