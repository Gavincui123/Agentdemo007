package com.agentdemo007.context;

import com.agentdemo007.access.PromptSanitizer;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.prompt.LocalPromptSource;
import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 上下文构建工厂入口测试（第五层·ContextBuilder，{@code @Order(700)}）。
 *
 * <p>委托 {@link ContextMerger} 拼接并写入 {@code assembledPrompt}；拼接异常走行为级降级
 * （§5.12 上下文构建为内部步骤、不直接面向用户→无话术、不短路）：兜底
 * [System(默认), User(sanitize(rawInput))] + Proceed，不标 degraded。
 */
class ContextBuilderTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-04T03:00:00Z"), ZoneOffset.ofHours(8));

    /** 用真实各层构建的 merger 验证主路径端到端。 */
    private ContextMerger realMerger() {
        return new ContextMerger(
                new SystemAnchorLayer(
                        new SystemPromptAssembler(new LocalPromptSource(),
                                SystemAnchorLayer.DEFAULT_SYSTEM_PROMPT),
                        FIXED_CLOCK),
                new UserMemoryLayer(),
                new ObjectiveDataLayer(),
                new UserInstructionLayer(new PromptSanitizer()));
    }

    @Test
    void normalPath_mergesAndWritesAssembledPrompt_returnsProceed() {
        ContextBuilder builder = new ContextBuilder(realMerger(), new PromptSanitizer());
        PipelineContext ctx = new PipelineContext("s", "你好");

        StepOutcome outcome = builder.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.assembledPrompt()).isNotEmpty();
        // Sys 在首、User 在尾（§5.5 隔离顺序）
        assertThat(ctx.assembledPrompt().get(0)).isInstanceOf(ChatMessage.System.class);
        assertThat(ctx.assembledPrompt().get(ctx.assembledPrompt().size() - 1))
                .isInstanceOf(ChatMessage.User.class);
    }

    @Test
    void mergerThrows_fallsBackToMinimalContext_returnsProceedNotDegrade() {
        ContextMerger failingMerger = Mockito.mock(ContextMerger.class);
        when(failingMerger.merge(any())).thenThrow(new RuntimeException("merger 故障"));
        PromptSanitizer sanitizer = new PromptSanitizer();
        ContextBuilder builder = new ContextBuilder(failingMerger, sanitizer);
        PipelineContext ctx = new PipelineContext("s", "救命问题");

        StepOutcome outcome = builder.process(ctx);

        // 行为级降级（§5.12 无话术行）：不标 degraded、不短路、Proceed
        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.degraded()).isFalse();
        // 兜底：[System(默认), User(sanitize(rawInput))]
        assertThat(ctx.assembledPrompt()).hasSize(2);
        assertThat(ctx.assembledPrompt().get(0)).isInstanceOf(ChatMessage.System.class);
        assertThat(ctx.assembledPrompt().get(0).content())
                .startsWith(SystemAnchorLayer.DEFAULT_SYSTEM_PROMPT);
        assertThat(ctx.assembledPrompt().get(1)).isInstanceOf(ChatMessage.User.class);
        assertThat(ctx.assembledPrompt().get(1).content()).startsWith(PromptSanitizer.OPEN);
        assertThat(ctx.assembledPrompt().get(1).content()).contains("救命问题");
    }
}
