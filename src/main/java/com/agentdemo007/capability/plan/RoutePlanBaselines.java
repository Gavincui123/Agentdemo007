package com.agentdemo007.capability.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * intent→确定性基线映射表（#132·[[routeplan-design]] 11+1 映射）。
 *
 * <p>每 intent 的确定性基线 = {@link RoutePlanCandidate}（必选工具/知识域/风险下限/fallback_policy）。
 * 收敛层 {@link RoutePlanRuleMatcher} 据此做 allowlist/risk_floor/workflow_boundary 收敛 + 确定性兜底
 * （source=DETERMINISTIC_FALLBACK, conf=0.75）。模型候选 invalid / 缺 route_model →
 * {@link #baselineFor} 直接兜底（路由环节永不崩溃，[[degradation-and-eval-principles]]）。
 *
 * <p>{@code needs_rag}/{@code needs_business_tools} 由 domains/tools 派生（自洽满足
 * {@link RoutePlanContractValidator} 4 跨字段约束——基线自身不可 invalid）。工具/域均 String 名
 * （真业务工具 get_order_logistics/get_refund_status/get_order_detail/search_products 待 ⑧接入，
 * [[routeplan-design]] 缺口优先级⑧；RoutePlan 子系统与 Intent 7→12 枚举对齐解耦，intent 名直接 String）。
 * 12th intent 待补（spec "11示+1待补"，未钦定不臆造）。
 */
public class RoutePlanBaselines {

    private static final Map<String, RoutePlanCandidate> BASELINES = seed();

    /** 按 intent 名取确定性基线；未知/null 返回 null（交收敛层走通用兜底）。 */
    public RoutePlanCandidate baselineFor(String intent) {
        if (intent == null) {
            return null;
        }
        return BASELINES.get(intent);
    }

    /** 全部已知 intent 名（基线自洽校验 + 路由收敛遍历用）。 */
    public Set<String> knownIntents() {
        return BASELINES.keySet();
    }

    private static RoutePlanCandidate baseline(String intent, List<String> tools, List<String> domains,
                                               RoutePlanCandidate.RiskLevel risk, boolean workflow,
                                               RoutePlanCandidate.FallbackPolicy fallback) {
        return new RoutePlanCandidate(intent, !domains.isEmpty(), !tools.isEmpty(),
                tools, domains, risk, workflow, fallback);
    }

    private static Map<String, RoutePlanCandidate> seed() {
        Map<String, RoutePlanCandidate> m = new LinkedHashMap<>();
        m.put("order_query", baseline("order_query",
                List.of("get_order_logistics"), List.of(),
                RoutePlanCandidate.RiskLevel.LOW, false, RoutePlanCandidate.FallbackPolicy.TOOL_FIRST));
        m.put("refund_status_query", baseline("refund_status_query",
                List.of("get_refund_status"), List.of(),
                RoutePlanCandidate.RiskLevel.LOW, false, RoutePlanCandidate.FallbackPolicy.TOOL_FIRST));
        m.put("refund_request", baseline("refund_request",
                List.of("get_order_detail"), List.of("after_sale_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true, RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST));
        m.put("return_request", baseline("return_request",
                List.of("get_order_detail"), List.of("received_return_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true, RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST));
        m.put("product_query", baseline("product_query",
                List.of("search_products"), List.of("promotion_and_member_policy"),
                RoutePlanCandidate.RiskLevel.LOW, false, RoutePlanCandidate.FallbackPolicy.TOOL_FIRST));
        m.put("faq_query", baseline("faq_query",
                List.of(), List.of("faq"),
                RoutePlanCandidate.RiskLevel.LOW, false, RoutePlanCandidate.FallbackPolicy.KNOWLEDGE_ONLY));
        m.put("promotion_query", baseline("promotion_query",
                List.of(), List.of("promotion_and_member_policy"),
                RoutePlanCandidate.RiskLevel.LOW, false, RoutePlanCandidate.FallbackPolicy.KNOWLEDGE_ONLY));
        m.put("low_confidence_query", baseline("low_confidence_query",
                List.of(), List.of("promotion_and_member_policy"),
                RoutePlanCandidate.RiskLevel.LOW, false, RoutePlanCandidate.FallbackPolicy.TRANSFER_TO_HUMAN));
        m.put("security_request", baseline("security_request",
                List.of(), List.of(),
                RoutePlanCandidate.RiskLevel.HIGH, false, RoutePlanCandidate.FallbackPolicy.TRANSFER_TO_HUMAN));
        m.put("degradation_request", baseline("degradation_request",
                List.of(), List.of(),
                RoutePlanCandidate.RiskLevel.MEDIUM, false, RoutePlanCandidate.FallbackPolicy.TRANSFER_TO_HUMAN));
        m.put("general_chat", baseline("general_chat",
                List.of(), List.of(),
                RoutePlanCandidate.RiskLevel.LOW, false, RoutePlanCandidate.FallbackPolicy.SAFE_DETERMINISTIC_PATH));
        return Map.copyOf(m);
    }
}
