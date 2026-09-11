package com.agentdemo007.intent;

import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.intent.rule.RuleMatcher;
import com.agentdemo007.session.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 多层级意图识别实现（第三层·§5.3.1 规则前置→小模型分类→兜底）。
 *
 * <p>层级顺序：
 * <ol>
 *   <li><b>规则前置</b>：{@link RuleMatcher#match} 命中（含注入胜出）→ 直接返回，零 LLM。
 *       注入（{@link Intent#INJECTION}）在此拦截，保证注入路径零 LLM（§5.11）。</li>
 *   <li><b>小模型分类</b>：规则无定论（冲突/无命中）→ 经 {@link ChatLlmService} 收口调用小模型
 *       （{@link Intent#CHIT_CHAT} 通道）分类，解析输出为 {@link Intent}。</li>
 *   <li><b>兜底</b>：模型不可用或输出不可解析 → {@link IntentCategory#unknown()}（{@link Intent#OTHER}，
 *       §5.12 兜底 UNKNOWN，不阻塞）。</li>
 * </ol>
 *
 * <p>注入二次扫描（§5.3.1 规则后置）：注入在规则前置层即拦截；更深层的"注入二次扫描"由
 * 接入层 {@code InputSecurityFilter}（前置门）与第七层输出安全过滤器（后置门）多层覆盖，
 * 本识别器不重复实现死代码（规则层与后置同库则后置恒为空操作）。收口：出站 LLM 调用只经
 * {@link ChatLlmService}（§9.11）。
 */
public class IntentRecognizerImpl implements IntentRecognizer {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognizerImpl.class);

    /** 小模型分类成功时的置信度（模型不返回置信度，按可达阈值设定）。 */
    private static final double MODEL_CONFIDENCE = 0.7;

    private final RuleMatcher ruleMatcher;
    private final ChatLlmService llm;

    public IntentRecognizerImpl(RuleMatcher ruleMatcher, ChatLlmService llm) {
        this.ruleMatcher = ruleMatcher;
        this.llm = llm;
    }

    @Override
    public IntentCategory recognize(String query, List<ChatMessage> history) {
        // 1. 规则前置（含注入胜出，零 LLM）
        Optional<IntentCategory> rule = ruleMatcher.match(query, history);
        if (rule.isPresent()) {
            return rule.get();
        }
        // 2. 小模型分类（规则无定论时升级）
        try {
            String prompt = buildClassifyPrompt(query, history);
            String reply = llm.decide(prompt);
            Intent parsed = parseIntent(reply);
            if (parsed == null) {
                log.debug("小模型输出不可解析，兜底 UNKNOWN：reply={}", reply);
                return IntentCategory.unknown();
            }
            return new IntentCategory(parsed, MODEL_CONFIDENCE);
        } catch (Exception e) {
            log.debug("意图识别小模型不可用，兜底 UNKNOWN：reason={}", e.getMessage());
            return IntentCategory.unknown();
        }
    }

    private String buildClassifyPrompt(String query, List<ChatMessage> history) {
        String transcript = (history == null || history.isEmpty())
                ? "（无历史）"
                : history.stream()
                        .map(m -> "[" + label(m) + "] " + m.content())
                        .collect(Collectors.joining("\n"));
        return "你是意图分类器。从下列候选意图中选一个最匹配的，只输出意图名（不附说明）：\n"
                + candidates() + "\n历史：\n" + transcript + "\n用户问题：" + query;
    }

    private String candidates() {
        StringBuilder sb = new StringBuilder();
        for (Intent i : Intent.values()) {
            sb.append(i.name()).append("(").append(i.description()).append(") ");
        }
        return sb.toString();
    }

    /** 解析模型输出为意图：先按枚举名（大小写不敏感），再按描述包含。 */
    private Intent parseIntent(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        String text = reply.trim();
        for (Intent i : Intent.values()) {
            if (i.name().equalsIgnoreCase(text)) {
                return i;
            }
        }
        for (Intent i : Intent.values()) {
            if (text.contains(i.description())) {
                return i;
            }
        }
        return null;
    }

    private String label(ChatMessage m) {
        if (m instanceof ChatMessage.User) return "用户";
        if (m instanceof ChatMessage.Ai) return "助手";
        if (m instanceof ChatMessage.System) return "系统";
        if (m instanceof ChatMessage.ToolResult) return "工具";
        return "消息";
    }
}
