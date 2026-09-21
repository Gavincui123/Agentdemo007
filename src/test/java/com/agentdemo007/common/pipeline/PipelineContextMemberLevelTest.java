package com.agentdemo007.common.pipeline;

import com.agentdemo007.capability.kb.KbLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PipelineContext 等级字段收口单测（Phase 21 T94）：缺省 V0（fail-closed）、null 归一 V0。
 */
class PipelineContextMemberLevelTest {

    @Test
    void defaultsToV0ForEvalAndAnonymous() {
        PipelineContext ctx = new PipelineContext("s1", "你好");
        assertThat(ctx.memberLevel()).isEqualTo(KbLevel.V0);
    }

    @Test
    void setterNormalizesNullToV0() {
        PipelineContext ctx = new PipelineContext("s1", "你好");
        ctx.setMemberLevel(KbLevel.V5);
        assertThat(ctx.memberLevel()).isEqualTo(KbLevel.V5);
        ctx.setMemberLevel(null);
        assertThat(ctx.memberLevel()).isEqualTo(KbLevel.V0);
    }
}
