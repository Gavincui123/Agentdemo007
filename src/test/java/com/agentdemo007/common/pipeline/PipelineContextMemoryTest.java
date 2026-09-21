package com.agentdemo007.common.pipeline;

import com.agentdemo007.capability.kb.KbLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PipelineContext Phase 22 字段归一化测试（memberProfile / lastUsageTokens）。
 */
class PipelineContextMemoryTest {

    @Test
    void memberProfile_blankNormalizedToNull() {
        PipelineContext ctx = new PipelineContext("s", "你好");

        assertThat(ctx.memberProfile()).isNull(); // 缺省无画像

        ctx.setMemberProfile("偏好：喜欢简洁");
        assertThat(ctx.memberProfile()).isEqualTo("偏好：喜欢简洁");

        ctx.setMemberProfile("   ");
        assertThat(ctx.memberProfile()).isNull(); // 空白归一 null
        ctx.setMemberProfile(null);
        assertThat(ctx.memberProfile()).isNull();
    }

    @Test
    void lastUsageTokens_nonPositiveNormalizedToNull() {
        PipelineContext ctx = new PipelineContext("s", "你好");

        assertThat(ctx.lastUsageTokens()).isNull(); // 缺省（话术短路/取消轮无 usage）

        ctx.setLastUsageTokens(960);
        assertThat(ctx.lastUsageTokens()).isEqualTo(960);

        ctx.setLastUsageTokens(0);
        assertThat(ctx.lastUsageTokens()).isNull(); // 引擎未回 usage（0）
        ctx.setLastUsageTokens(-1);
        assertThat(ctx.lastUsageTokens()).isNull();
        ctx.setLastUsageTokens(null);
        assertThat(ctx.lastUsageTokens()).isNull();
    }

    @Test
    void memberLevel_defaultStillV0_failClosed() {
        // Phase 21 回归：memberLevel 缺省 V0 不受 Phase 22 改动影响
        assertThat(new PipelineContext("s", "x").memberLevel()).isEqualTo(KbLevel.V0);
    }
}
