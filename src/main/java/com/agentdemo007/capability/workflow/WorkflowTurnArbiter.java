package com.agentdemo007.capability.workflow;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.session.model.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 会话仲裁器（2026-09-18 方案A·用户裁决）：开放性对话里"退货→退款→给单号→算了不退了"这类
 * 跨轮语义（新意图还是延续？订单号绑定哪个动作？撤销还是澄清？）规则无法准确识别，交大模型
 * 结合上下文推理。<b>不推翻现有状态机</b>——仲裁器是 @670 确定性分支之上的升级层：
 * <ul>
 *   <li><b>触发受控</b>：仅三种状态机搞不定的情形调用（活跃提交的语义消歧 / 多动作绑定 /
 *       pending 冲突），其余轮零成本；</li>
 *   <li><b>降级回退</b>：LLM 缺席/失败/输出不合法 → Optional.empty() → 调用方走原确定性分支
 *       （现行为完全保留，既有测试零感知）；</li>
 *   <li><b>小模型通道</b>：经 {@link ChatLlmService#chatRaw}（scene=会话仲裁，关思考，~1-2s），
 *       出站收口 §9.11。</li>
 * </ul>
 * 判定空间（JSON，宽松解析）：{@code CONTINUE_ACTIVE}（问进度/寒暄式重复）/
 * {@code WITHDRAW}（撤销已提交售后）/{@code BIND_RUN}（订单号绑定某售后动作，intent 必填且须为
 * refund_request/return_request）/{@code CLARIFY}（仍不明确，需澄清）。
 */
public class WorkflowTurnArbiter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTurnArbiter.class);

    private static final Set<String> DECISIONS =
            Set.of("CONTINUE_ACTIVE", "WITHDRAW", "BIND_RUN", "CLARIFY");
    private static final Set<String> AFTER_SALE_INTENTS = Set.of("refund_request", "return_request");
    private static final int HISTORY_TAIL = 6;

    private final ChatLlmService llm; // 可空（部分单测/dev 桩）：null=仲裁停用，恒走确定性分支
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkflowTurnArbiter(ChatLlmService llm) {
        this.llm = llm;
    }

    /** 仲裁结论：decision ∈ 判定空间；BIND_RUN 时 intent 为绑定的售后意图；reason 为审计依据。 */
    public record Arbitration(String decision, String intent, String reason) {
        public boolean isWithdraw() {
            return "WITHDRAW".equals(decision);
        }

        public boolean isClarify() {
            return "CLARIFY".equals(decision);
        }

        public boolean isBindRun() {
            return "BIND_RUN".equals(decision) && intent != null && AFTER_SALE_INTENTS.contains(intent);
        }
    }

    /**
     * 仲裁当前轮。任何失败（LLM 缺席/异常/空回复/JSON 不合法/判定越界）→ empty（确定性回退）。
     *
     * @param pendingIntent 会话待澄清动作（可空）
     * @param active        活跃售后提交（可空）
     * @param recentActions 会话近期涉及的售后动作（去重序列，判多动作绑定用）
     */
    public Optional<Arbitration> arbitrate(PipelineContext context, String pendingIntent,
                                           WorkflowSubmissionRegistry.Entry active, List<String> recentActions) {
        if (llm == null) {
            return Optional.empty();
        }
        try {
            String prompt = buildPrompt(context, pendingIntent, active, recentActions);
            if (log.isDebugEnabled()) {
                log.debug("提交LLM的会话仲裁指令（面向模型）：sessionId={}\n{}", context.sessionId(), prompt);
            }
            String reply = llm.chatRaw(prompt,
                    (context.intent() != null) ? context.intent() : Intent.OTHER, "会话仲裁");
            Arbitration arbitration = parse(reply);
            if (arbitration == null) {
                log.warn("会话仲裁输出不合法，回退确定性分支：sessionId={} reply={}",
                        context.sessionId(), abbreviate(reply));
                return Optional.empty();
            }
            log.info("会话仲裁产出：sessionId={} decision={} intent={} reason={}", // 审计
                    context.sessionId(), arbitration.decision(), arbitration.intent(), arbitration.reason());
            return Optional.of(arbitration);
        } catch (Exception e) {
            log.warn("会话仲裁失败，回退确定性分支：sessionId={} reason={}", context.sessionId(), e.getMessage());
            return Optional.empty();
        }
    }

    private String buildPrompt(PipelineContext context, String pendingIntent,
                               WorkflowSubmissionRegistry.Entry active, List<String> recentActions) {
        StringBuilder sb = new StringBuilder(
                "你是电商客服的会话决策器。客户的问题往往是开放性的（退货/退款/撤销/查进度混杂），"
                        + "请结合对话历史与系统状态，判断用户本轮消息的真实意图动作。\n")
                .append("对话历史（最近几轮）：\n").append(transcript(context.history()))
                .append("\n系统状态：\n")
                .append("- 待澄清动作：").append(pendingIntent == null ? "无" : pendingIntent).append('\n')
                .append("- 近期涉及的售后动作：")
                .append(recentActions == null || recentActions.isEmpty() ? "无" : String.join("、", recentActions))
                .append('\n');
        if (active != null) {
            sb.append("- 已提交售后单：").append(active.action()).append("，订单 ")
                    .append(active.orderId()).append("，售后单号 ")
                    .append(active.workflowId() == null ? "未知" : active.workflowId()).append("（处理中）\n");
        } else {
            sb.append("- 已提交售后单：无\n");
        }
        sb.append("用户本轮消息：").append(currentQuery(context)).append('\n')
                .append("只能输出以下判定之一：\n")
                .append("- CONTINUE_ACTIVE：用户在询问进度/确认/寒暄式重复，无需新动作\n")
                .append("- WITHDRAW：用户想撤销/放弃已提交的售后申请\n")
                .append("- BIND_RUN：本轮给出了订单号且明确要办理某个售后动作，intent 填 refund_request 或 return_request\n")
                .append("- CLARIFY：意图仍不明确，需向用户澄清（不得编造订单状态或政策结论）\n")
                .append("只输出 JSON，不要解释：{\"decision\":\"...\",\"intent\":\"...\",\"reason\":\"一句话依据\"}");
        return sb.toString();
    }

    private String transcript(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "（无）";
        }
        List<ChatMessage> tail = history.size() <= HISTORY_TAIL
                ? history : history.subList(history.size() - HISTORY_TAIL, history.size());
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : tail) {
            sb.append('[').append(label(m)).append("] ").append(m.content()).append('\n');
        }
        return sb.toString();
    }

    private static String label(ChatMessage m) {
        if (m instanceof ChatMessage.User) return "用户";
        if (m instanceof ChatMessage.Ai) return "助手";
        if (m instanceof ChatMessage.System) return "系统";
        if (m instanceof ChatMessage.ToolResult) return "工具";
        return "消息";
    }

    private static String currentQuery(PipelineContext context) {
        if (context.standardQuery() != null && context.standardQuery().text() != null
                && !context.standardQuery().text().isBlank()) {
            return context.standardQuery().text();
        }
        return context.rawInput();
    }

    /** 宽松解析：截取首个 {...}；判定越界/BIND_RUN 缺合法 intent → null（回退）。 */
    private Arbitration parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        try {
            int start = reply.indexOf('{');
            int end = reply.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode node = mapper.readTree(reply.substring(start, end + 1));
            String decision = node.path("decision").asText("").trim().toUpperCase(Locale.ROOT);
            if (!DECISIONS.contains(decision)) {
                return null;
            }
            String intent = node.path("intent").asText(null);
            if ("BIND_RUN".equals(decision) && (intent == null || !AFTER_SALE_INTENTS.contains(intent))) {
                return null;
            }
            String reason = node.path("reason").asText("");
            return new Arbitration(decision, intent, reason);
        } catch (Exception e) {
            return null;
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return (s.length() <= 80) ? s : s.substring(0, 80) + "…";
    }
}
