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

    /** 滚动摘要契约上界（T101：60 字锚点口径放宽为 ≤200 字滚动摘要，测试钉死）。 */
    static final int ROLLING_MAX_CHARS = 200;

    private final ChatLlmService llm;

    public LlmSummaryHook(ChatLlmService llm) {
        this.llm = llm;
    }

    @Override
    public Optional<String> summarize(List<ChatMessage> priorHistory, String currentInput) {
        // 新会话首轮（历史为空）：锚点=用户原话，零 LLM——对单条消息做 LLM 转述零增益，
        // 且限流期实测该调用阻塞新会话首字 16.6s（2026-09-17：20.6s 轮中 16.6s 在摘要）。
        if (priorHistory == null || priorHistory.isEmpty()) {
            return Optional.ofNullable(currentInput).filter(s -> !s.isBlank());
        }
        try {
            String prompt = buildPrompt(priorHistory, currentInput);
            String summary = llm.chat(prompt, Intent.CHIT_CHAT, "会话摘要");
            if (summary == null || summary.isBlank()) {
                return Optional.empty();
            }
            String trimmed = summary.trim();
            if (isGarbage(trimmed)) {
                // 锚点要写入 System 提示并喂给后续轮次——垃圾摘要（模型回显提示词碎片/超长跑偏）
                // 会污染整个会话上下文，宁缺毋滥（②降级：无锚点继续推进）
                log.warn("摘要输出疑似垃圾（含花括号或超长），丢弃锚点：len={}", trimmed.length());
                return Optional.empty();
            }
            return Optional.of(trimmed);
        } catch (Exception e) {
            log.warn("摘要生成失败（降级跳过锚点）：reason={}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 垃圾摘要判定：含 '{'/'}'（提示词/JSON 碎片回显）或超过 60 字（一句话概括的正常上界）。 */
    private static boolean isGarbage(String summary) {
        return summary.indexOf('{') >= 0 || summary.indexOf('}') >= 0 || summary.length() > 60;
    }

    /** 滚动摘要垃圾判定（T101 契约：上界放宽至 ≤200 字；花括号碎片回显判定继承）。 */
    private static boolean isGarbageRolling(String summary) {
        return summary.indexOf('{') >= 0 || summary.indexOf('}') >= 0 || summary.length() > ROLLING_MAX_CHARS;
    }

    @Override
    public Optional<String> summarizeRolling(String oldSummary, List<ChatMessage> slidOut) {
        if (slidOut == null || slidOut.isEmpty()) {
            return Optional.empty();
        }
        try {
            String prompt = buildRollingPrompt(oldSummary, slidOut);
            String summary = llm.chat(prompt, Intent.CHIT_CHAT, "滚动摘要");
            if (summary == null || summary.isBlank()) {
                return Optional.empty();
            }
            String trimmed = summary.trim();
            if (isGarbageRolling(trimmed)) {
                // 滚动摘要要长期驻留 System 块——垃圾/超长（>200 字）宁缺毋滥（②降级：调用方沿用旧摘要）
                log.warn("滚动摘要输出疑似垃圾（含花括号或超 {} 字），丢弃：len={}", ROLLING_MAX_CHARS, trimmed.length());
                return Optional.empty();
            }
            return Optional.of(trimmed);
        } catch (Exception e) {
            log.warn("滚动摘要生成失败（降级沿用旧摘要）：reason={}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 滚动摘要指令：增量合并（旧摘要 + 新滑出轮次），业务键（订单号等）原样保留。 */
    private String buildRollingPrompt(String oldSummary, List<ChatMessage> slidOut) {
        String transcript = slidOut.stream()
                .map(m -> "[" + label(m) + "] " + m.content())
                .collect(Collectors.joining("\n"));
        String prior = (oldSummary == null || oldSummary.isBlank()) ? "（无）" : oldSummary;
        return "你是电商客服系统的会话摘要助手。请把「已有摘要」与「新对话段」合并为一段不超过 200 字的滚动摘要，"
                + "作为后续对话的背景锚点（仅输出摘要，不要附加说明）。要求：\n"
                + "1. 保留用户诉求主线与尚未了结的事项（是否已解决/待跟进）；\n"
                + "2. 出现的单号等业务标识（如 ORD-001）必须原样保留，不得改写或省略；\n"
                + "3. 已有摘要仍成立的内容直接继承，不重复展开。\n"
                + "已有摘要：" + prior + "\n新对话段：\n" + transcript;
    }

    private String buildPrompt(List<ChatMessage> priorHistory, String currentInput) {
        String transcript = priorHistory.isEmpty()
                ? "（无历史，本轮为首句）"
                : priorHistory.stream()
                        .map(m -> "[" + label(m) + "] " + m.content())
                        .collect(Collectors.joining("\n"));
        return "你是电商客服系统的会话摘要助手。请用一句话概括以下会话的主题，"
                + "作为后续上下文锚点（仅输出摘要，不要附加说明）：\n"
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
