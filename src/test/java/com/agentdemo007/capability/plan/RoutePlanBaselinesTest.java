package com.agentdemo007.capability.plan;

import org.junit.jupiter.api.Test;

import static com.agentdemo007.capability.plan.RoutePlanCandidate.FallbackPolicy;
import static com.agentdemo007.capability.plan.RoutePlanCandidate.RiskLevel;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoutePlanBaselines 测试（#132·intent→确定性基线映射表，[[routeplan-design]] 11+1 映射）。
 *
 * <p>每 intent 的确定性基线 = {@link RoutePlanCandidate}（必选工具/知识域/风险下限/fallback_policy）。
 * 收敛层 RoutePlanRuleMatcher 据此做 allowlist/risk_floor/workflow_boundary 收敛 + 确定性兜底
 * （source=DETERMINISTIC_FALLBACK, conf=0.75）。工具/域均 String 名（真业务工具 get_order_logistics/
 * get_refund_status/get_order_detail/search_products 待 ⑧接入，[[routeplan-design]] 缺口优先级⑧）。
 *
 * <p>基线须满足 {@link RoutePlanContractValidator} 4 跨字段约束（自洽，否则自身即 invalid）。
 */
class RoutePlanBaselinesTest {

    private final RoutePlanBaselines baselines = new RoutePlanBaselines();

    @Test
    void orderQuery_toolOnly_low_toolFirst() {
        RoutePlanCandidate b = baselines.baselineFor("order_query");
        assertThat(b).isNotNull();
        assertThat(b.requiredTools()).containsExactly("get_order_logistics");
        assertThat(b.knowledgeDomains()).isEmpty();
        assertThat(b.needsBusinessTools()).isTrue();
        assertThat(b.needsRag()).isFalse();
        assertThat(b.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(b.requiresWorkflow()).isFalse();
        assertThat(b.fallbackPolicy()).isEqualTo(FallbackPolicy.TOOL_FIRST);
    }

    @Test
    void refundRequest_toolAndRag_high_workflow_workflowFirst() {
        RoutePlanCandidate b = baselines.baselineFor("refund_request");
        assertThat(b.requiredTools()).containsExactly("get_order_detail");
        assertThat(b.knowledgeDomains()).containsExactly("after_sale_policy");
        assertThat(b.needsBusinessTools()).isTrue();
        assertThat(b.needsRag()).isTrue();
        assertThat(b.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(b.requiresWorkflow()).isTrue();
        assertThat(b.fallbackPolicy()).isEqualTo(FallbackPolicy.WORKFLOW_FIRST);
    }

    @Test
    void returnRequest_toolAndRag_high_workflow_workflowFirst() {
        RoutePlanCandidate b = baselines.baselineFor("return_request");
        assertThat(b.requiredTools()).containsExactly("get_order_detail");
        assertThat(b.knowledgeDomains()).containsExactly("received_return_policy");
        assertThat(b.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(b.requiresWorkflow()).isTrue();
        assertThat(b.fallbackPolicy()).isEqualTo(FallbackPolicy.WORKFLOW_FIRST);
    }

    @Test
    void productQuery_toolAndRag_low_toolFirst() {
        RoutePlanCandidate b = baselines.baselineFor("product_query");
        assertThat(b.requiredTools()).containsExactly("search_products");
        assertThat(b.knowledgeDomains()).containsExactly("promotion_and_member_policy");
        assertThat(b.fallbackPolicy()).isEqualTo(FallbackPolicy.TOOL_FIRST);
    }

    @Test
    void faqQuery_ragOnly_low_knowledgeOnly() {
        RoutePlanCandidate b = baselines.baselineFor("faq_query");
        assertThat(b.requiredTools()).isEmpty();
        assertThat(b.knowledgeDomains()).containsExactly("faq");
        assertThat(b.needsRag()).isTrue();
        assertThat(b.needsBusinessTools()).isFalse();
        assertThat(b.fallbackPolicy()).isEqualTo(FallbackPolicy.KNOWLEDGE_ONLY);
    }

    @Test
    void generalChat_empty_low_safeDeterministicPath() {
        RoutePlanCandidate b = baselines.baselineFor("general_chat");
        assertThat(b.requiredTools()).isEmpty();
        assertThat(b.knowledgeDomains()).isEmpty();
        assertThat(b.needsRag()).isFalse();
        assertThat(b.needsBusinessTools()).isFalse();
        assertThat(b.requiresWorkflow()).isFalse();
        assertThat(b.fallbackPolicy()).isEqualTo(FallbackPolicy.SAFE_DETERMINISTIC_PATH);
    }

    @Test
    void securityRequest_empty_high_transferToHuman() {
        RoutePlanCandidate b = baselines.baselineFor("security_request");
        assertThat(b.requiredTools()).isEmpty();
        assertThat(b.knowledgeDomains()).isEmpty();
        assertThat(b.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(b.fallbackPolicy()).isEqualTo(FallbackPolicy.TRANSFER_TO_HUMAN);
    }

    @Test
    void degradationRequest_empty_medium_transferToHuman() {
        RoutePlanCandidate b = baselines.baselineFor("degradation_request");
        assertThat(b.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(b.fallbackPolicy()).isEqualTo(FallbackPolicy.TRANSFER_TO_HUMAN);
    }

    @Test
    void allBaselines_satisfyCrossFieldContract() {
        RoutePlanContractValidator validator = new RoutePlanContractValidator();
        for (String intent : baselines.knownIntents()) {
            RoutePlanCandidate b = baselines.baselineFor(intent);
            assertThat(validator.validate(b))
                    .as("基线 %s 须满足 4 跨字段约束（自洽）", intent)
                    .isEmpty();
        }
    }

    @Test
    void unknownIntent_returnsNull() {
        assertThat(baselines.baselineFor("no_such_intent")).isNull();
    }
}
