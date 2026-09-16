package com.agentdemo007.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptSegmentTest {

    @Test
    void from_fullMap_mapsAllFields() {
        Map<?, ?> m = Map.of(
                "id", "role", "prompt", "你是客服", "scene", "refund_request",
                "sort", 80, "enabled", true);
        PromptSegment s = PromptSegment.from(m);
        assertThat(s.id()).isEqualTo("role");
        assertThat(s.prompt()).isEqualTo("你是客服");
        assertThat(s.scene()).isEqualTo("refund_request");
        assertThat(s.sort()).isEqualTo(80);
        assertThat(s.enabled()).isTrue();
    }

    @Test
    void from_missingScene_defaultsToAll() {
        Map<?, ?> m = Map.of("id", "x", "prompt", "p", "sort", 1, "enabled", true);
        assertThat(PromptSegment.from(m).scene()).isEqualTo(PromptSegment.SCENE_ALL);
    }

    @Test
    void from_missingSort_defaultsToZero() {
        Map<?, ?> m = Map.of("id", "x", "prompt", "p", "scene", "all", "enabled", true);
        assertThat(PromptSegment.from(m).sort()).isZero();
    }

    @Test
    void from_missingEnabled_defaultsToTrue() {
        Map<?, ?> m = Map.of("id", "x", "prompt", "p", "scene", "all", "sort", 1);
        assertThat(PromptSegment.from(m).enabled()).isTrue();
    }

    @Test
    void from_nullMap_returnsNull() {
        assertThat(PromptSegment.from(null)).isNull();
    }
}
