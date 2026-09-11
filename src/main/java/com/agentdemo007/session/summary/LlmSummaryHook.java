package com.agentdemo007.session.summary;

import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.session.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 摘要 Hook 的 LLM 实现：经 {@link ChatLlmService} 收口调用小模型（{@link Intent#CHIT_CHAT} 通道）
 * 生成会话摘要锚点。
 *
 * <p>任何异常/空输出 → {@code Optional.empty()}（②每步降级：摘要缺失不阻塞，调用方跳过锚点继续）。
 * 收口：出站 LLM 调用只经 {@link ChatLlmService}，不在此直连引擎（§9.11）。
 */
@Component
public class LlmSummaryHook implements SummaryHook {

    private static final Logger log = LoggerFactory.getLogger(LlmSummaryHook.class);

    private final ChatLlmService llm;

    public LlmSummaryHook(ChatLlmService llm) {
        this.llm = llm;
    }

    @Override
    public Optional<String> summarize(List<ChatMessage> priorHistory, String currentInput) {
        try {
            String prompt = buildPrompt(priorHistory, currentInput);
            String summary = llm.chat(prompt, Intent.CHIT_CHAT);
            if (summary == null || summary.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(summary.trim());
        } catch (Exception e) {
            log.warn("摘要生成失败（降级跳过锚点）：reason={}", e.getMessage());
            return Optional.empty();
        }
    }

    private String buildPrompt(List<ChatMessage> priorHistory, String currentInput) {
        String transcript = priorHistory.isEmpty()
                ? "（无历史，本轮为首句）"
                : priorHistory.stream()
                        .map(m -> "[" + label(m) + "] " + m.content())
                        .collect(Collectors.joining("\n"));
        return "请用一句话概括以下会话的主题，作为后续上下文锚点（仅输出摘要，不要附加说明）：\n"
                + transcript + "\n本轮用户输入：" + currentInput;
    }

    private String label(ChatMessage m) {
        if (m instanceof ChatMessage.User) return "用户";
        if (m instanceof ChatMessage.Ai) return "助手";
        if (m instanceof ChatMessage.System) return "系统";
        if (m instanceof ChatMessage.ToolResult) return "工具";
        return "消息";
    }
}
