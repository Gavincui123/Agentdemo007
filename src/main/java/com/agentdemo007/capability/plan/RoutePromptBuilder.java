package com.agentdemo007.capability.plan;

import com.agentdemo007.session.model.ChatMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * route_model prompt 构造器（#134·Sub-slice 2·为 {@link LlmRouteCandidateSource} 预构 prompt）。
 *
 * <p>prompt 须给模型足够约束以产出一个合法的 8 字段 snake_case JSON（{@link RouteCandidateParser}
 * 可解析）：角色指令 + 8 字段规格 + intent/tool/domain/risk/fallback 选项空间（单一真源——
 * intent 与 tool_candidates 从 {@link RoutePlanBaselines} 派生，不臆造）+ 会话历史 + 当前 query。
 *
 * <p>本类只构造 prompt 字符串（单一职责），不调 LLM——出站决策调用归 {@link LlmRouteCandidateSource}
 * （经 {@link com.agentdemo007.gateway.llm.ChatLlmService#decide} 关思考，[[phase-llm-primary-backup-breaker]]）。
 * 调用方 {@code RoutePlanStep}（#134·Sub-slice 4）拿 prompt 喂 {@link RoutePlanner#plan}。
 *
 * <p>历史 transcript 复用 {@code QueryRewriter} 的 {@code [角色] 内容} 模式（Phase 4 引擎无关消息收口）。
 */
public class RoutePromptBuilder {

    /**
     * 11 已知业务意图的一句话业务定义（与 {@link RoutePlanBaselines} 的 knownIntents 一一对应；
     * {@link #intentDefinitions()} 按 knownIntents 顺序取用，新意图缺定义时退化为仅意图名不崩）。
     *
     * <p>prompt 工程核心与 {@link com.agentdemo007.intent.IntentRecognizerImpl} 同款：一句话定义远比
     * 枚举名可依赖。2026-09-28 线上事故（『退款流程是什么』被字面「退款」带偏成 refund_request →
     * entity-gate 澄清要订单号 + 售后收敛关闭 RAG）补齐路由层——此前 11 个意图只有名字没有定义，
     * 两个 few-shot 又全是退款请求，小模型无咨询/办理分界依据。
     */
    private static final Map<String, String> INTENT_DEFINITIONS = Map.ofEntries(
            Map.entry("order_query", "查询已有订单的状态/物流/详情（要『查』，不是要『办』）"),
            Map.entry("refund_status_query", "查询已提交退款的办理进度或结果"),
            Map.entry("refund_request", "用户明确表达办理退款的动作诉求（如『我要退款』『帮我退了这笔订单』）"),
            Map.entry("return_request", "用户明确表达办理退货的动作诉求（如『我要退货』）"),
            Map.entry("product_query", "商品咨询：在售商品、参数、推荐"),
            Map.entry("faq_query", "咨询政策/流程/规则等知识性信息（『XX是什么/流程怎么走/政策怎么规定/需要什么条件』），包括退款退货的政策与流程"),
            Map.entry("promotion_query", "促销活动与会员权益咨询"),
            Map.entry("low_confidence_query", "无法理解用户诉求"),
            Map.entry("security_request", "账号安全类问题：盗号、封禁、诈骗"),
            Map.entry("degradation_request", "用户明确要求人工服务"),
            Map.entry("general_chat", "与业务无关的闲聊"));

    /** 4 知识域（[[routeplan-design]] 固定集，不随 intent 变）。 */
    private static final List<String> KNOWLEDGE_DOMAINS = List.of(
            "faq", "after_sale_policy", "received_return_policy", "promotion_and_member_policy");

    /** 3 风险档（{@link RoutePlanCandidate.RiskLevel} 的 snake_case 小写，模型须照此输出）。 */
    private static final List<String> RISK_LEVELS = List.of("low", "medium", "high");

    /** 6 fallback_policy（{@link RoutePlanCandidate.FallbackPolicy} 的 snake_case 小写）。 */
    private static final List<String> FALLBACK_POLICIES = List.of(
            "safe_deterministic_path", "ask_order_id", "knowledge_only",
            "tool_first", "workflow_first", "transfer_to_human");

    /** 10 字段规格（模型须照此输出 snake_case JSON，{@link RouteCandidateParser} 解析）。 */
    private static final List<String> FIELD_SPEC = List.of(
            "intent", "needs_rag", "needs_business_tools", "required_tools",
            "knowledge_domains", "risk_level", "requires_workflow", "fallback_policy",
            "ambiguous", "secondary_intent");

    private final RoutePlanBaselines baselines;
    private final List<String> toolCandidates;

    public RoutePromptBuilder(RoutePlanBaselines baselines) {
        this.baselines = baselines;
        this.toolCandidates = deriveToolCandidates(baselines);
    }

    /**
     * 构造 route prompt。
     *
     * @param history 会话历史（可为 null/空 → 标记无历史）
     * @param query  当前查询（标准化查询或原问题；可为 null）
     * @return route_model prompt 字符串（永不 null/空）
     */
    public String build(List<ChatMessage> history, String query) {
        return build(history, query, null);
    }

    public String build(List<ChatMessage> history, String query, String pendingIntent) {
        String transcript = transcript(history);
        String q = query == null ? "（空）" : query;
        StringBuilder sb = new StringBuilder();
        sb.append("你是电商客服系统的路由决策器。根据对话历史与用户本轮问题，产出一份路由候选 JSON。")
          .append("只输出 JSON，不要附加说明。字段如下（snake_case）：\n")
          .append(FIELD_SPEC.stream().map(f -> "  - " + f).collect(Collectors.joining("\n"))).append("\n")
          .append("约束：intent 只能从以下选一个：").append(String.join(" / ", baselines.knownIntents())).append("；")
          .append(intentDefinitions())
          .append("分类原则：询问政策/流程/条件/规则的知识性问题（『是什么/怎么规定/流程怎么走/需要什么条件』）属于 faq_query")
          .append("——即使内容涉及退款/退货；refund_request/return_request 只用于明确的办理动作诉求（『我要退款/帮我退货』）。\n")
          .append("required_tools 只能从 tool_candidates 选，不可发明工具；")
          .append("knowledge_domains 只能从以下 4 域选：").append(String.join(" / ", KNOWLEDGE_DOMAINS)).append("；")
          .append("risk_level 只能是：").append(String.join(" / ", RISK_LEVELS)).append("；")
          .append("fallback_policy 只能是：").append(String.join(" / ", FALLBACK_POLICIES)).append("；")
          .append("requires_workflow=true 时 risk_level 必须 high 且 fallback_policy 必须 workflow_first。\n")
          .append("高风险售后动作（refund_request / return_request）必须：requires_workflow=true、risk_level=high、")
          .append("fallback_policy=workflow_first、needs_rag=true、required_tools=[\"get_order_detail\"]；")
          .append("其余 intent requires_workflow=false。")
          .append("跨字段一致性：knowledge_domains 非空则 needs_rag=true；required_tools 非空则 needs_business_tools=true。\n")
          .append("ambiguous：若用户本轮问题含多个相互冲突或反复变更的意图（如既退款又退货、『不要退货要退款』反复、")
          .append("否定矛盾如『不退款』），置 ambiguous=true；单一清晰意图置 false。ambiguous=true 时仍填你最可能的 intent/字段，")
          .append("但系统将改走澄清而非执行。\n")
          .append("secondary_intent：仅当本轮含售后动作（refund_request/return_request）且另有明确可独立执行的诉求时填，")
          .append("只能是 order_query / refund_status_query / product_query / promotion_query / faq_query 之一，")
          .append("不得等于 intent；其余情况留空（null）。\n");
        if (pendingIntent != null) {
            // 2026-09-17 收紧（实测「ord-001退款」被 pending 偏置成 return_request 续跑退货流）：
            // 显式业务诉求 > 未完成意图；只有"纯补单号"才允许沿用 pending intent。
            sb.append("本会话有未完成的 ").append(pendingIntent)
              .append(" 等待订单号（Turn-2 续跑参考）。优先级：用户本轮的显式业务诉求永远高于未完成意图——")
              .append("仅当本轮只是补充订单号、未提及任何其他业务动作时，intent 才填 ").append(pendingIntent)
              .append("；本轮一旦显式提到其他动作（如退款/退货/查订单/商品咨询），一律按本轮诉求填 intent，")
              .append("不要被未完成意图带跑。政策/流程类咨询（如『XX流程是什么』）一律按 faq_query 等咨询意图处理，")
              .append("不受未完成意图影响。\n");
        }
        sb.append("tool_candidates：").append(String.join(" / ", toolCandidates)).append("\n")
          .append("示例（咨询退款政策/流程——知识性问题，非办理退款）：{\"intent\":\"faq_query\",\"needs_rag\":true,\"needs_business_tools\":false,")
          .append("\"required_tools\":[],\"knowledge_domains\":[\"after_sale_policy\"],")
          .append("\"risk_level\":\"low\",\"requires_workflow\":false,\"fallback_policy\":\"knowledge_only\",")
          .append("\"ambiguous\":false}\n")
          .append("示例（退款请求）：{\"intent\":\"refund_request\",\"needs_rag\":true,\"needs_business_tools\":true,")
          .append("\"required_tools\":[\"get_order_detail\"],\"knowledge_domains\":[\"after_sale_policy\"],")
          .append("\"risk_level\":\"high\",\"requires_workflow\":true,\"fallback_policy\":\"workflow_first\",")
          .append("\"ambiguous\":false}\n")
          .append("示例（退款+买耳机 独立双诉求）：{\"intent\":\"refund_request\",\"needs_rag\":true,\"needs_business_tools\":true,")
          .append("\"required_tools\":[\"get_order_detail\"],\"knowledge_domains\":[\"after_sale_policy\"],")
          .append("\"risk_level\":\"high\",\"requires_workflow\":true,\"fallback_policy\":\"workflow_first\",")
          .append("\"ambiguous\":false,\"secondary_intent\":\"product_query\"}\n")
          .append("历史：\n").append(transcript).append("\n用户本轮问题：").append(q);
        return sb.toString();
    }

    /** 意图定义块（按 baselines.knownIntents 顺序产出，防定义集与意图集漂移；缺定义退化为仅意图名）。 */
    private String intentDefinitions() {
        StringBuilder sb = new StringBuilder("意图定义（按定义选 intent，勿按字面关键词）：\n");
        for (String intent : baselines.knownIntents()) {
            String def = INTENT_DEFINITIONS.get(intent);
            sb.append("  - ").append(intent).append("(").append(def != null ? def : intent).append(")\n");
        }
        return sb.toString();
    }

    /** tool_candidates = 全 baseline requiredTools 并集（单一真源，排序保确定）。 */
    private static List<String> deriveToolCandidates(RoutePlanBaselines baselines) {
        Set<String> tools = new TreeSet<>();
        for (String intent : baselines.knownIntents()) {
            RoutePlanCandidate b = baselines.baselineFor(intent);
            if (b != null) {
                tools.addAll(b.requiredTools());
            }
        }
        return List.copyOf(tools);
    }

    private static String transcript(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "（无历史，本轮为首句）";
        }
        return history.stream()
                .map(m -> "[" + label(m) + "] " + m.content())
                .collect(Collectors.joining("\n"));
    }

    private static String label(ChatMessage m) {
        if (m instanceof ChatMessage.User) return "用户";
        if (m instanceof ChatMessage.Ai) return "助手";
        if (m instanceof ChatMessage.System) return "系统";
        if (m instanceof ChatMessage.ToolResult) return "工具";
        return "消息";
    }
}
