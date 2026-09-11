package com.agentdemo007.capability.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoutePlanCandidate 跨字段契约校验测试（4 约束 + 1 合法，参见 [[routeplan-design]]）。
 *
 * <p>模型产出的 8 字段候选须过 4 条跨字段约束，不过 → invalid_model_route_candidate → 确定性兜底。
 */
class RoutePlanContractValidatorTest {

    private final RoutePlanContractValidator validator = new RoutePlanContractValidator();

    private RoutePlanCandidate candidate(boolean rag, boolean biz, List<String> tools,
                                         List<String> domains, RoutePlanCandidate.RiskLevel risk,
                                         boolean wf, RoutePlanCandidate.FallbackPolicy fp) {
        return new RoutePlanCandidate("general_chat", rag, biz, tools, domains, risk, wf, fp);
    }

    @Test
    void validRefundRequest_noErrors() {
        // refund_request 基线：tools=[get_order_detail] domains=[after_sale_policy] risk=HIGH wf=true fp=WORKFLOW_FIRST
        RoutePlanCandidate c = candidate(true, true,
                List.of("get_order_detail"), List.of("after_sale_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true, RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST);
        assertThat(validator.validate(c)).isEmpty();
    }

    @Test
    void toolsPresent_butBusinessToolsFalse_violatesConstraint1() {
        RoutePlanCandidate c = candidate(false, false,
                List.of("get_order_logistics"), List.of(),
                RoutePlanCandidate.RiskLevel.LOW, false, RoutePlanCandidate.FallbackPolicy.TOOL_FIRST);
        List<String> errors = validator.validate(c);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("needs_business_tools");
    }

    @Test
    void domainsPresent_butRagFalse_violatesConstraint2() {
        RoutePlanCandidate c = candidate(false, true,
                List.of(), List.of("faq"),
                RoutePlanCandidate.RiskLevel.LOW, false, RoutePlanCandidate.FallbackPolicy.KNOWLEDGE_ONLY);
        List<String> errors = validator.validate(c);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("needs_rag");
    }

    @Test
    void workflowTrue_butRiskNotHigh_violatesConstraint3() {
        // wf=true 但 risk=LOW（fp 故意设 WORKFLOW_FIRST 以不触发约束4，隔离约束3）
        RoutePlanCandidate c = candidate(true, true,
                List.of("get_order_detail"), List.of("after_sale_policy"),
                RoutePlanCandidate.RiskLevel.LOW, true, RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST);
        List<String> errors = validator.validate(c);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("risk_level");
    }

    @Test
    void workflowTrue_butFallbackNotWorkflowFirst_violatesConstraint4() {
        // wf=true 但 fp=TOOL_FIRST（risk 故意设 HIGH 以不触发约束3，隔离约束4）
        RoutePlanCandidate c = candidate(true, true,
                List.of("get_order_detail"), List.of("after_sale_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true, RoutePlanCandidate.FallbackPolicy.TOOL_FIRST);
        List<String> errors = validator.validate(c);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("workflow_first");
    }
}
