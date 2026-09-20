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

    /** 非空历史（LLM 摘要路径的前提；空历史走零 LLM 原话锚点，见 summarize_emptyHistory 用例）。 */
    private static final List<ChatMessage> HISTORY =
            List.of(new ChatMessage.User("帮我看看 Q3 销售"), new ChatMessage.Ai("数据如下"));

    @Test
    void summarize_emptyHistory_returnsRawInput_zeroLlm() {
        // 2026-09-17 定案回归钉：新会话首轮锚点=用户原话，零 LLM
        // （限流期实测 LLM 转述单条消息阻塞首字 16.6s）
        Optional<String> summary = hook.summarize(List.of(), "我要查询物流");

        assertThat(summary).contains("我要查询物流");
        org.mockito.Mockito.verify(llm, org.mockito.Mockito.never())
                .chat(anyString(), eq(Intent.CHIT_CHAT), eq("会话摘要"));
    }

    @Test
    void summarize_withHistoryOnly_callsLlm() {
        // LLM 摘要只在有真实历史时触发（新会话首轮走零 LLM 原话锚点）
        when(llm.chat(anyString(), eq(Intent.CHIT_CHAT), eq("会话摘要"))).thenReturn("会话主题：Q3 销售复盘");

        Optional<String> summary = hook.summarize(
                List.of(new ChatMessage.User("帮我看看 Q3 销售"), new ChatMessage.Ai("数据如下")), "对比 Q2 呢");

        assertThat(summary).contains("会话主题：Q3 销售复盘");
    }

    @Test
    void summarize_existingHistory_returnsLlmSummary() {
        when(llm.chat(anyString(), eq(Intent.CHIT_CHAT), eq("会话摘要"))).thenReturn("会话主题：报表生成");

        Optional<String> summary = hook.summarize(
                List.of(new ChatMessage.User("生成报表"), new ChatMessage.Ai("已生成")), "再画一张");

        assertThat(summary).contains("会话主题：报表生成");
    }

    @Test
    void summarize_llmFailure_returnsEmpty() {
        when(llm.chat(anyString(), eq(Intent.CHIT_CHAT), eq("会话摘要"))).thenThrow(new RuntimeException("model down"));

        assertThat(hook.summarize(HISTORY, "你好")).isEmpty();
    }

    @Test
    void summarize_blankOutput_returnsEmpty() {
        when(llm.chat(anyString(), eq(Intent.CHIT_CHAT), eq("会话摘要"))).thenReturn("   ");

        assertThat(hook.summarize(HISTORY, "你好")).isEmpty();
    }

    @Test
    void summarize_promptEchoGarbage_returnsEmpty() {
        // 实测事故：模型回显提示词碎片（含花括号）→ 垃圾锚点污染后续 System 提示——须丢弃
        when(llm.chat(anyString(), eq(Intent.CHIT_CHAT), eq("会话摘要")))
                .thenReturn("用户当前输出锚点摘要锚'}");

        assertThat(hook.summarize(HISTORY, "帮我开增值税专用票")).isEmpty();
    }

    @Test
    void summarize_overlyLongOutput_returnsEmpty() {
        when(llm.chat(anyString(), eq(Intent.CHIT_CHAT), eq("会话摘要"))).thenReturn("一".repeat(80));

        assertThat(hook.summarize(HISTORY, "你好")).isEmpty();
    }
}
