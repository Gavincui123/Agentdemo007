package com.agentdemo007.capability.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * RoutePlanCandidate 跨字段契约校验器（4 约束，参考系统 {@code validate_cross_field_contract}）。
 *
 * <p>4 跨字段约束（[[routeplan-design]]）：
 * <ol>
 *   <li>required_tools 非空 → needs_business_tools 必须 true</li>
 *   <li>knowledge_domains 非空 → needs_rag 必须 true</li>
 *   <li>requires_workflow=true → risk_level 必须 high</li>
 *   <li>requires_workflow=true → fallback_policy 必须 workflow_first</li>
 * </ol>
 *
 * <p>返回错误列表（空=通过）。校验不过 → 候选视为 None → {@code invalid_model_route_candidate}
 * → 确定性兜底意图（路由永不崩溃）。本类纯函数，无 LLM、无副作用，可独立 TDD。
 */
public class RoutePlanContractValidator {

    public List<String> validate(RoutePlanCandidate c) {
        List<String> errors = new ArrayList<>();
        if (!c.requiredTools().isEmpty() && !c.needsBusinessTools()) {
            errors.add("required_tools 非空时 needs_business_tools 必须为 true");
        }
        if (!c.knowledgeDomains().isEmpty() && !c.needsRag()) {
            errors.add("knowledge_domains 非空时 needs_rag 必须为 true");
        }
        if (c.requiresWorkflow() && c.riskLevel() != RoutePlanCandidate.RiskLevel.HIGH) {
            errors.add("requires_workflow=true 时 risk_level 必须为 high");
        }
        if (c.requiresWorkflow() && c.fallbackPolicy() != RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST) {
            errors.add("requires_workflow=true 时 fallback_policy 必须为 workflow_first");
        }
        return errors;
    }
}
