package com.agentdemo007.session.rewrite;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.session.model.ChatMessage;
import com.agentdemo007.session.model.StandardQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 用户问题改写步骤测试（第二层·QueryRewriter @Order(300)）。
 *
 * <p>{@link QueryRewriter} 结合历史上下文，经 {@link ChatLlmService} 收口调用小模型
 * （{@link Intent#CHIT_CHAT} 通道）将指代/省略/口语化问题改写为自足 Query，写入
 * {@code context.standardQuery}（§5.2.2）。
 *
 * <p>②每步降级：改写 LLM 失败/空输出 → 回退 {@code rawInput} 继续（不阻塞，§5.12 行为级降级，无话术）。
 */
class QueryRewriterTest {

    private final ChatLlmService llm = mock(ChatLlmService.class);
    private final QueryRewriter rewriter = new QueryRewriter(llm);

    @Test
    void rewrite_success_writesStandardQuery_andProceeds() {
        when(llm.decide(anyString(), anyString())).thenReturn("Q3 销售额怎么样");
        PipelineContext ctx = new PipelineContext("s1", "那它呢");
        ctx.setHistory(List.of(new ChatMessage.User("看 Q3 销售"), new ChatMessage.Ai("好的")));

        StepOutcome outcome = rewriter.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("Q3 销售额怎么样"));
    }

    @Test
    void rewrite_llmFailure_fallsBackToRawInput_andProceeds() {
        when(llm.decide(anyString(), anyString())).thenThrow(new RuntimeException("model down"));
        PipelineContext ctx = new PipelineContext("s1", "那它呢");

        StepOutcome outcome = rewriter.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("那它呢")); // 回退原问题
    }

    @Test
    void rewrite_blankOutput_fallsBackToRawInput_andProceeds() {
        when(llm.decide(anyString(), anyString())).thenReturn("   ");
        PipelineContext ctx = new PipelineContext("s1", "它怎么样");

        StepOutcome outcome = rewriter.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("它怎么样"));
    }

    @Test
    void rewrite_firstTurn_noHistory_skipsLlm_usesRawInput() {
        // 空历史（首句/新会话）无指代可消解 → 跳过改写小模型（省 0.8~2.6s 一整轮 LLM，2026-09-18 延迟优化）
        PipelineContext ctx = new PipelineContext("new", "帮我查 Q3 销售");

        StepOutcome outcome = rewriter.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("帮我查 Q3 销售"));
        org.mockito.Mockito.verifyNoInteractions(llm); // 零 LLM
    }

    @Test
    void chitChatTriaged_skipsLlm_usesRawInput() {
        // 模拟前置 KeywordTriageStep(@Order 150) 已分诊 chit-chat：intent 已设
        PipelineContext ctx = new PipelineContext("s1", "你好");
        ctx.setIntent(Intent.CHIT_CHAT);

        StepOutcome outcome = rewriter.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("你好")); // 直用原问题，不改写
        verifyNoInteractions(llm); // chit-chat 已分诊 → 不调改写小模型（零 LLM）
    }

    @Test
    void name_isQueryRewriter() {
        assertThat(rewriter.name()).isEqualTo("QueryRewriter");
    }
}
