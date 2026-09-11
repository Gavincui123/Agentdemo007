package com.agentdemo007.session.router;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.session.model.ChatMessage;
import com.agentdemo007.session.summary.SummaryHook;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话路由步骤测试（第二层·SessionRouter @Order(200)）。
 *
 * <p>{@link SessionRouter} 在 {@code SessionLoadStep} 装载历史后决策命中/新建：
 * <ul>
 *   <li>新建（历史为空）→ 触发 {@link SummaryHook} 生成摘要锚点写入 {@code context.summary} + {@link StepOutcome.Proceed}；</li>
 *   <li>命中（历史非空）→ 不触发 Hook，直接 Proceed（历史已由加载步回填）；</li>
 *   <li>Hook 不可用（empty）→ 不阻塞，无锚点继续推进（②每步降级）。</li>
 * </ul>
 */
class SessionRouterTest {

    private final SummaryHook hook = mock(SummaryHook.class);
    private final SessionRouter router = new SessionRouter(hook);

    @Test
    void newSession_triggersHook_writesSummaryAnchor_andProceeds() {
        when(hook.summarize(any(), eq("帮我看看 Q3 销售"))).thenReturn(Optional.of("主题：Q3 销售"));
        PipelineContext ctx = new PipelineContext("new-1", "帮我看看 Q3 销售");
        // 新建会话：历史为空（SessionLoadStep 未回填或回填空）

        StepOutcome outcome = router.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.summary()).isEqualTo("主题：Q3 销售");
        verify(hook).summarize(any(), eq("帮我看看 Q3 销售"));
    }

    @Test
    void newSession_hookUnavailable_noAnchorStillProceeds() {
        when(hook.summarize(any(), anyString())).thenReturn(Optional.empty());
        PipelineContext ctx = new PipelineContext("new-2", "你好");

        StepOutcome outcome = router.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.summary()).isNull(); // 降级：无锚点，不阻塞
    }

    @Test
    void existingSession_doesNotTriggerHook_andProceeds() {
        PipelineContext ctx = new PipelineContext("hit-1", "那它呢");
        ctx.setHistory(List.of(new ChatMessage.User("聊 A"), new ChatMessage.Ai("答 A")));

        StepOutcome outcome = router.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.summary()).isNull();
        verify(hook, never()).summarize(any(), anyString()); // 命中会话不触发摘要
    }

    @Test
    void name_isSessionRouter() {
        assertThat(router.name()).isEqualTo("SessionRouter");
    }
}
