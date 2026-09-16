package com.agentdemo007.capability.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoutePlan（收敛后 consumed 形态）测试（#132·② RoutePlan 回炉，[[routeplan-design]]）。
 *
 * <p>旧 3 字段（capabilities/mode/serialOrder）已退役——mode/serialOrder 移至 #135 运行时 DAG
 * （串并行按工具依赖元数据动态定，非路由决策）；consumed 形态 = 收敛后 {@link RoutePlanCandidate}
 * + Source + confidence + policyConstraints。本测聚焦数据载体契约（决策逻辑归 RoutePlanRuleMatcher，非 RoutePlan）。
 */
class RoutePlanTest {

    private static RoutePlanCandidate candidate(boolean rag, boolean tools, boolean workflow) {
        return new RoutePlanCandidate(
                "general_chat", rag, tools, List.of(), List.of(),
                RoutePlanCandidate.RiskLevel.LOW, workflow,
                RoutePlanCandidate.FallbackPolicy.SAFE_DETERMINISTIC_PATH);
    }

    @Test
    void wrap_holdsCandidateSourceConfidencePolicyConstraints() {
        RoutePlanCandidate c = candidate(true, false, false);
        RoutePlan p = new RoutePlan(c, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9,
                List.of("structured_candidate_validated", "tool_allowlist"));
        assertThat(p.candidate()).isSameAs(c);
        assertThat(p.source()).isEqualTo(RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS);
        assertThat(p.confidence()).isEqualTo(0.9);
        assertThat(p.policyConstraints()).containsExactly("structured_candidate_validated", "tool_allowlist");
    }

    @Test
    void compact_ctor_defaultsNullPolicyConstraintsToEmpty() {
        RoutePlan p = new RoutePlan(candidate(false, false, false),
                RoutePlan.Source.DETERMINISTIC_FALLBACK, 0.75, null);
        assertThat(p.policyConstraints()).isEmpty();
    }

    @Test
    void isEmpty_whenNoRagNoToolsNoWorkflow() {
        RoutePlan p = new RoutePlan(candidate(false, false, false),
                RoutePlan.Source.DETERMINISTIC_FALLBACK, 0.75, List.of());
        assertThat(p.isEmpty()).isTrue();
    }

    @Test
    void notEmpty_whenAnyCapability() {
        assertThat(new RoutePlan(candidate(true, false, false),
                RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, List.of()).isEmpty()).isFalse();
        assertThat(new RoutePlan(candidate(false, true, false),
                RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, List.of()).isEmpty()).isFalse();
        assertThat(new RoutePlan(candidate(false, false, true),
                RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, List.of()).isEmpty()).isFalse();
    }

    @Test
    void delegates_flattenAccessorsToCandidate() {
        RoutePlanCandidate c = new RoutePlanCandidate(
                "refund_request", true, true, List.of("get_order_detail"),
                List.of("after_sale_policy"), RoutePlanCandidate.RiskLevel.HIGH, true,
                RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST);
        RoutePlan p = new RoutePlan(c, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, List.of());
        assertThat(p.intent()).isEqualTo("refund_request");
        assertThat(p.needsRag()).isTrue();
        assertThat(p.needsBusinessTools()).isTrue();
        assertThat(p.requiredTools()).containsExactly("get_order_detail");
        assertThat(p.knowledgeDomains()).containsExactly("after_sale_policy");
        assertThat(p.riskLevel()).isEqualTo(RoutePlanCandidate.RiskLevel.HIGH);
        assertThat(p.requiresWorkflow()).isTrue();
        assertThat(p.fallbackPolicy()).isEqualTo(RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST);
    }

    @Test
    void deterministic_factory_setsSourceFallbackAndConfidence() {
        RoutePlanCandidate baseline = candidate(false, false, false);
        RoutePlan p = RoutePlan.deterministic(baseline);
        assertThat(p.source()).isEqualTo(RoutePlan.Source.DETERMINISTIC_FALLBACK);
        assertThat(p.confidence()).isEqualTo(0.75);
        assertThat(p.policyConstraints()).isEmpty();
        assertThat(p.candidate()).isSameAs(baseline);
    }

    @Test
    void candidate_ambiguousAndSecondary_defaults() {
        RoutePlanCandidate c = new RoutePlanCandidate(
                "refund_request", true, true,
                List.of("get_order_detail"), List.of("after_sale_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true,
                RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST);
        assertThat(c.ambiguous()).isFalse();          // 8 参兼容构造 → 默认 false
        assertThat(c.secondaryIntent()).isNull();     // 默认 null
    }

    @Test
    void candidate_withAmbiguous_flipsFlag() {
        RoutePlanCandidate c = new RoutePlanCandidate(
                "refund_request", true, true,
                List.of("get_order_detail"), List.of("after_sale_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true,
                RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST,
                true, "product_query");
        assertThat(c.ambiguous()).isTrue();
        assertThat(c.secondaryIntent()).isEqualTo("product_query");
        assertThat(c.withAmbiguous(false).ambiguous()).isFalse();
    }

    @Test
    void routePlan_exposesAmbiguousAndSecondary() {
        RoutePlanCandidate c = new RoutePlanCandidate(
                "refund_request", true, true,
                List.of("get_order_detail"), List.of("after_sale_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true,
                RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST, true, "product_query");
        RoutePlan rp = new RoutePlan(c, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, List.of());
        assertThat(rp.ambiguous()).isTrue();
        assertThat(rp.secondaryIntent()).isEqualTo("product_query");
    }
}
