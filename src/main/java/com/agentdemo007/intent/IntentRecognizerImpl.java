package com.agentdemo007.intent;

import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.intent.rule.RuleMatcher;
import com.agentdemo007.session.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
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

    /**
     * 模型可选意图的业务级定义（提示词工程核心）：一句话定义远比枚举名可依赖——跨模型稳定性的
     * 关键在任务定义 + 分类原则 + few-shot 示例，而非模型自身"常识"。
     * {@link Intent#TRANSFER_TO_HUMAN}（用户显式诉求，关键词层 0.95 触发）与 {@link Intent#INJECTION}
     * （规则层独占，零 LLM）不进模型候选——实测模型把"帮我开增值税专用票"判成转人工 →
     * HitlStep 建工单短路，业务问题被话术劫持（意图漂移）。
     */
    private static final Map<Intent, String> MODEL_CANDIDATES = Map.of(
            Intent.CHIT_CHAT, "闲聊：问候、寒暄、情绪表达、与业务无关的闲谈",
            Intent.REASONING, "推理：需要计算、对比、分析、总结或解释因果的问题",
            Intent.LONG_CONTEXT, "长上下文：用户提供大段材料并要求基于材料处理",
            Intent.STRUCTURED_EXTRACTION, "结构化抽取：从用户给定的文本中提取字段或结构化信息",
            Intent.OTHER, "其他：咨询、办理、投诉等业务诉求，且不属于以上任何类别");

    private String buildClassifyPrompt(String query, List<ChatMessage> history) {
        String transcript = buildTranscript(history);
        return """
                你是电商客服系统的意图分类器。任务：判断【用户最新问题】属于哪个意图类别。

                ## 分类原则
                1. 只依据用户的显式诉求分类；不要根据"系统能否办理该业务"改变分类，办理能力由系统下游决定。
                2. "转人工/人工服务"类诉求不在候选中：仅当用户明确要求人工服务时由关键词规则处理，你不要输出。
                3. 无法确定时选 OTHER，不要臆测。

                ## 候选意图（名称(定义)）
                %s

                ## 示例（问题 → 意图）
                你好，在吗 → CHIT_CHAT
                2加3乘4等于多少 → REASONING
                帮我看看怎么开增值税发票 → OTHER
                把用户给的这段地址信息整理成省市区格式 → STRUCTURED_EXTRACTION
                今天天气不错哈哈 → CHIT_CHAT

                ## 输出格式
                只输出一行意图名（大写英文，如 CHIT_CHAT），不要输出任何解释、标点或其他内容。

                ## 会话背景（仅供理解指代与省略，分类只针对最新问题）
                %s
                用户最新问题：%s""".formatted(candidates(), transcript, query);
    }

    /** 候选清单：仅模型可选意图，逐个带业务定义（排除 TRANSFER_TO_HUMAN/INJECTION，见 MODEL_CANDIDATES）。 */
    private String candidates() {
        StringBuilder sb = new StringBuilder();
        MODEL_CANDIDATES.forEach((intent, definition) ->
                sb.append(intent.name()).append("(").append(definition).append(")\n"));
        return sb.toString();
    }

    /** 会话背景转写：仅近 6 条（分类只需近期指代线索，长历史稀释注意力）。 */
    private String buildTranscript(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "（无历史）";
        }
        return history.stream()
                .skip(Math.max(0, history.size() - 6))
                .map(m -> "[" + label(m) + "] " + m.content())
                .collect(Collectors.joining("\n"));
    }

    /** 解析模型输出为意图：先按枚举名（大小写不敏感），再按描述包含；规则层独占意图不解析。 */
    private Intent parseIntent(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        String text = reply.trim();
        for (Intent i : Intent.values()) {
            if (isModelSelectable(i) && i.name().equalsIgnoreCase(text)) {
                return i;
            }
        }
        for (Intent i : Intent.values()) {
            if (isModelSelectable(i) && text.contains(i.description())) {
                return i;
            }
        }
        return null;
    }

    /** 模型可否输出该意图：TRANSFER_TO_HUMAN（显式诉求走关键词层）与 INJECTION（规则层独占）除外。 */
    private static boolean isModelSelectable(Intent intent) {
        return intent != Intent.TRANSFER_TO_HUMAN && intent != Intent.INJECTION;
    }

    private String label(ChatMessage m) {
        if (m instanceof ChatMessage.User) return "用户";
        if (m instanceof ChatMessage.Ai) return "助手";
        if (m instanceof ChatMessage.System) return "系统";
        if (m instanceof ChatMessage.ToolResult) return "工具";
        return "消息";
    }
}
