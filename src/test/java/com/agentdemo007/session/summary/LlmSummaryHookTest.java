package com.agentdemo007.session.summary;

import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 摘要 Hook 测试（第二层·新建会话触发摘要锚点）。
 *
 * <p>{@link LlmSummaryHook} 经 {@link ChatLlmService} 收口调用小模型生成会话摘要；
 * 任何失败/空输出 → {@code Optional.empty()}（②每步降级：摘要缺失不阻塞，调用方跳过锚点）。
 *
 * <p>注入 mock {@link ChatLlmService}，断言走小模型通道（{@link Intent#CHIT_CHAT}）。
 */
class LlmSummaryHookTest {

    private final ChatLlmService llm = mock(ChatLlmService.class);
    private final LlmSummaryHook hook = new LlmSummaryHook(llm);

    @Test
    void summarize_newSession_returnsLlmSummary() {
        when(llm.chat(anyString(), eq(Intent.CHIT_CHAT))).thenReturn("用户询问 Q3 销售数据");

        Optional<String> summary = hook.summarize(List.of(), "帮我看看 Q3 销售");

        assertThat(summary).contains("用户询问 Q3 销售数据");
    }

    @Test
    void summarize_existingHistory_returnsLlmSummary() {
        when(llm.chat(anyString(), eq(Intent.CHIT_CHAT))).thenReturn("会话主题：报表生成");

        Optional<String> summary = hook.summarize(
                List.of(new ChatMessage.User("生成报表"), new ChatMessage.Ai("已生成")), "再画一张");

        assertThat(summary).contains("会话主题：报表生成");
    }

    @Test
    void summarize_llmFailure_returnsEmpty() {
        when(llm.chat(anyString(), eq(Intent.CHIT_CHAT))).thenThrow(new RuntimeException("model down"));

        assertThat(hook.summarize(List.of(), "你好")).isEmpty();
    }

    @Test
    void summarize_blankOutput_returnsEmpty() {
        when(llm.chat(anyString(), eq(Intent.CHIT_CHAT))).thenReturn("   ");

        assertThat(hook.summarize(List.of(), "你好")).isEmpty();
    }

    @Test
    void summarize_promptEchoGarbage_returnsEmpty() {
        // 实测事故：模型回显提示词碎片（含花括号）→ 垃圾锚点污染后续 System 提示——须丢弃
        when(llm.chat(anyString(), eq(Intent.CHIT_CHAT)))
                .thenReturn("用户当前输出锚点摘要锚'}");

        assertThat(hook.summarize(List.of(), "帮我开增值税专用票")).isEmpty();
    }

    @Test
    void summarize_overlyLongOutput_returnsEmpty() {
        when(llm.chat(anyString(), eq(Intent.CHIT_CHAT))).thenReturn("一".repeat(80));

        assertThat(hook.summarize(List.of(), "你好")).isEmpty();
    }
}
