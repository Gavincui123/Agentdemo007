package com.agentdemo007.capability.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.agentdemo007.capability.plan.RoutePlan.Source;
import static com.agentdemo007.capability.plan.RoutePlanCandidate.FallbackPolicy;
import static com.agentdemo007.capability.plan.RoutePlanCandidate.RiskLevel;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RoutePlanRuleMatcher} 收敛层测试（#132·服务端白名单收敛，[[routeplan-design]]）。
 *
 * <p>5 政策约束（required_entity_gate 待实体抽取，⑦后接入，本切片 5 项）：
 * <ol>
 *   <li>structured_candidate_validated — 4 跨字段契约（{@link RoutePlanContractValidator}），不过→兜底；</li>
 *   <li>tool_allowlist — candidate.requiredTools ⊆ baseline.requiredTools（不发明工具）；</li>
 *   <li>knowledge_domain_allowlist — candidate.knowledgeDomains ⊆ baseline.knowledgeDomains（不跨域）；</li>
 *   <li>risk_floor — candidate.risk ≥ baseline.risk（不降风险下限）；</li>
 *   <li>workflow_boundary — baseline 工作流 → candidate 须工作流（不降级动作意图）。</li>
 * </ol>
 * 全过 → 采纳候选（source=LLM_WITH_POLICY_CONSTRAINTS, conf=0.9, 审计 5 名）；任一违 → 确定性兜底
 * （baseline 候选, source=DETERMINISTIC_FALLBACK, conf=0.75, [违例名]）。路由永不崩溃（[[degradation-and-eval-principles]]）。
 */
class RoutePlanRuleMatcherTest {

    private final RoutePlanRuleMatcher matcher = new RoutePlanRuleMatcher(
            new RoutePlanContractValidator(), new RoutePlanBaselines());

    private static RoutePlanCandidate cand(String intent, List<String> tools, List<String> domains,
                                           RiskLevel risk, boolean workflow, FallbackPolicy fallback) {
        return new RoutePlanCandidate(intent, !domains.isEmpty(), !tools.isEmpty(),
                tools, domains, risk, workflow, fallback);
    }

    @Test
    void validCandidateMatchingBaseline_adopted_llmSource_conf09_audit5() {
        RoutePlanCandidate c = cand("order_query",
                List.of("get_order_logistics"), List.of(),
                RiskLevel.LOW, false, FallbackPolicy.TOOL_FIRST);
        RoutePlan p = matcher.converge(c);
        assertThat(p.source()).isEqualTo(Source.LLM_WITH_POLICY_CONSTRAINTS);
        assertThat(p.confidence()).isEqualTo(0.9);
        assertThat(p.candidate()).isSameAs(c);
        assertThat(p.policyConstraints()).containsExactly(
                "structured_candidate_validated", "tool_allowlist",
                "knowledge_domain_allowlist", "risk_floor", "workflow_boundary");
    }

    @Test
    void contractInvalid_fallsBack_deterministic_baseline() {
        // requiredTools 非空 + needsBusinessTools=false → 约束1 违反
        RoutePlanCandidate c = new RoutePlanCandidate("order_query", false, false,
                List.of("get_order_logistics"), List.of(),
                RiskLevel.LOW, false, FallbackPolicy.TOOL_FIRST);
        RoutePlan p = matcher.converge(c);
        assertThat(p.source()).isEqualTo(Source.DETERMINISTIC_FALLBACK);
        assertThat(p.confidence()).isEqualTo(0.75);
        assertThat(p.intent()).isEqualTo("order_query");
        assertThat(p.requiredTools()).containsExactly("get_order_logistics");
        assertThat(p.policyConstraints()).containsExactly("invalid_model_route_candidate");
    }

    @Test
    void toolInvention_fallsBack_toolAllowlistViolation() {
        RoutePlanCandidate c = cand("order_query",
                List.of("get_order_logistics", "invented_tool"), List.of(),
                RiskLevel.LOW, false, FallbackPolicy.TOOL_FIRST);
        RoutePlan p = matcher.converge(c);
        assertThat(p.source()).isEqualTo(Source.DETERMINISTIC_FALLBACK);
        assertThat(p.policyConstraints()).containsExactly("tool_allowlist_violation");
        assertThat(p.requiredTools()).containsExactly("get_order_logistics");
    }

    @Test
    void domainInvention_fallsBack_domainAllowlistViolation() {
        RoutePlanCandidate c = cand("refund_request",
                List.of("get_order_detail"), List.of("after_sale_policy", "invented_domain"),
                RiskLevel.HIGH, true, FallbackPolicy.WORKFLOW_FIRST);
        RoutePlan p = matcher.converge(c);
        assertThat(p.source()).isEqualTo(Source.DETERMINISTIC_FALLBACK);
        assertThat(p.policyConstraints()).containsExactly("knowledge_domain_allowlist_violation");
    }

    @Test
    void riskBelowFloor_fallsBack_riskFloorViolation() {
        // security_request baseline risk=HIGH, workflow=false → candidate risk=MEDIUM 合同有效但 < floor
        RoutePlanCandidate c = cand("security_request",
                List.of(), List.of(),
                RiskLevel.MEDIUM, false, FallbackPolicy.TRANSFER_TO_HUMAN);
        RoutePlan p = matcher.converge(c);
        assertThat(p.source()).isEqualTo(Source.DETERMINISTIC_FALLBACK);
        assertThat(p.policyConstraints()).containsExactly("risk_floor_violation");
        assertThat(p.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void workflowBoundaryViolation_fallsBack() {
        // refund_request baseline workflow=true → candidate workflow=false 违反 workflow_boundary。
        // risk 须=HIGH（=baseline 下限）否则先撞 risk_floor，无法隔离 workflow_boundary 单违例。
        RoutePlanCandidate c = cand("refund_request",
                List.of("get_order_detail"), List.of("after_sale_policy"),
                RiskLevel.HIGH, false, FallbackPolicy.TOOL_FIRST);
        RoutePlan p = matcher.converge(c);
        assertThat(p.source()).isEqualTo(Source.DETERMINISTIC_FALLBACK);
        assertThat(p.policyConstraints()).containsExactly("workflow_boundary_violation");
        assertThat(p.requiresWorkflow()).isTrue();
    }

    @Test
    void unknownIntent_fallsBack_generalChatBaseline() {
        RoutePlanCandidate c = cand("mystery_intent",
                List.of(), List.of(),
                RiskLevel.LOW, false, FallbackPolicy.SAFE_DETERMINISTIC_PATH);
        RoutePlan p = matcher.converge(c);
        assertThat(p.source()).isEqualTo(Source.DETERMINISTIC_FALLBACK);
        assertThat(p.intent()).isEqualTo("general_chat");
        assertThat(p.isEmpty()).isTrue();
    }

    @Test
    void nullCandidate_fallsBack_generalChat_neverCrashes() {
        RoutePlan p = matcher.converge(null);
        assertThat(p.source()).isEqualTo(Source.DETERMINISTIC_FALLBACK);
        assertThat(p.intent()).isEqualTo("general_chat");
    }

    @Test
    void converge_violation_preservesAmbiguous() {
        RoutePlanRuleMatcher m = new RoutePlanRuleMatcher(new RoutePlanContractValidator(), new RoutePlanBaselines());
        // 违例候选：required_tools 含基线没有的工具 → tool_allowlist_violation → 兜底
        RoutePlanCandidate c = new RoutePlanCandidate("refund_request", true, true,
                List.of("invented_tool"), List.of("after_sale_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true,
                RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST, true, null);
        RoutePlan rp = m.converge(c);
        assertThat(rp.source()).isEqualTo(RoutePlan.Source.DETERMINISTIC_FALLBACK);
        assertThat(rp.ambiguous()).isTrue();   // 兜底不丢 LLM 的 ambiguous
    }

    @Test
    void converge_violation_dropsSecondary() {
        RoutePlanRuleMatcher m = new RoutePlanRuleMatcher(new RoutePlanContractValidator(), new RoutePlanBaselines());
        RoutePlanCandidate c = new RoutePlanCandidate("refund_request", true, true,
                List.of("invented_tool"), List.of("after_sale_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true,
                RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST, false, "product_query");
        RoutePlan rp = m.converge(c);
        assertThat(rp.secondaryIntent()).isNull();  // secondary 丢弃→退化单腿
    }
}
