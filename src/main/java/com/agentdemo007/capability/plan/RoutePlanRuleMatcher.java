package com.agentdemo007.capability.plan;

import java.util.List;

/**
 * 服务端白名单收敛层（#132·[[routeplan-design]] 第四层·convergence）。
 *
 * <p>route_model 产出的 {@link RoutePlanCandidate} 经本层 5 政策约束收敛（required_entity_gate
 * 待实体抽取，⑦后接入，本切片 5 项）：
 * <ol>
 *   <li><b>structured_candidate_validated</b> — 委托 {@link RoutePlanContractValidator} 4 跨字段约束，
 *       不过 → {@code invalid_model_route_candidate} 兜底；</li>
 *   <li><b>tool_allowlist</b> — candidate.requiredTools ⊆ baseline.requiredTools
 *       （不发明工具，违→{@code tool_allowlist_violation}）；</li>
 *   <li><b>knowledge_domain_allowlist</b> — candidate.knowledgeDomains ⊆ baseline.knowledgeDomains
 *       （不跨域，违→{@code knowledge_domain_allowlist_violation}）；</li>
 *   <li><b>risk_floor</b> — candidate.risk ≥ baseline.risk（不降风险下限，违→{@code risk_floor_violation}，
 *       按 {@link RoutePlanCandidate.RiskLevel} 声明序 LOW&lt;MEDIUM&lt;HIGH 比较）；</li>
 *   <li><b>workflow_boundary</b> — baseline 工作流 → candidate 须工作流（不降级动作意图，
 *       违→{@code workflow_boundary_violation}）。</li>
 * </ol>
 *
 * <p>5 项全过 → 采纳候选（source=LLM_WITH_POLICY_CONSTRAINTS, conf=0.9, 审计 5 名）；任一违 →
 * 确定性兜底（取该 intent 的 {@link RoutePlanBaselines} 基线候选, source=DETERMINISTIC_FALLBACK,
 * conf=0.75, [违例名]）。未知 intent / null 候选 → general_chat 基线兜底。
 * <b>路由永不崩溃</b>（[[degradation-and-eval-principles]]：收敛层兜底而非抛）。
 *
 * <p>本类纯函数（无 LLM、无副作用），决策可独立 TDD；#133 RoutePlanner 混合层（rule→LLM→rule兜底）
 * 在模型不可用时直接调 {@link #converge} 走纯确定性路径。
 */
public class RoutePlanRuleMatcher {

    private static final List<String> FULL_AUDIT = List.of(
            "structured_candidate_validated", "tool_allowlist",
            "knowledge_domain_allowlist", "risk_floor", "workflow_boundary");

    private final RoutePlanContractValidator contractValidator;
    private final RoutePlanBaselines baselines;

    public RoutePlanRuleMatcher(RoutePlanContractValidator contractValidator, RoutePlanBaselines baselines) {
        this.contractValidator = contractValidator;
        this.baselines = baselines;
    }

    /**
     * 收敛模型候选 → route_plan。5 政策约束顺序校验，首违即兜底（短路，不再续判）。
     *
     * @param candidate route_model 产出的候选（可 null——模型全挂时由 #133 传 null 走纯兜底）
     * @return 采纳则 LLM_WITH_POLICY_CONSTRAINTS(0.9, 5 审计)；违例则 DETERMINISTIC_FALLBACK(0.75, [违例名])
     */
    public RoutePlan converge(RoutePlanCandidate candidate) {
        if (candidate == null) {
            return fallback(null, "null_candidate");
        }
        // 1. structured_candidate_validated — 4+1 跨字段契约
        if (!contractValidator.validate(candidate).isEmpty()) {
            return fallback(candidate, "invalid_model_route_candidate");
        }
        RoutePlanCandidate baseline = baselines.baselineFor(candidate.intent());
        if (baseline == null) {
            return fallback(null, "unknown_intent_no_baseline");
        }
        // 2. tool_allowlist — 不发明工具
        if (!baseline.requiredTools().containsAll(candidate.requiredTools())) {
            return fallback(candidate, "tool_allowlist_violation");
        }
        // 3. knowledge_domain_allowlist — 不跨域
        if (!baseline.knowledgeDomains().containsAll(candidate.knowledgeDomains())) {
            return fallback(candidate, "knowledge_domain_allowlist_violation");
        }
        // 4. risk_floor — candidate.risk ≥ baseline.risk（LOW<MEDIUM<HIGH 声明序）
        if (candidate.riskLevel().ordinal() < baseline.riskLevel().ordinal()) {
            return fallback(candidate, "risk_floor_violation");
        }
        // 5. workflow_boundary — baseline 工作流 → candidate 须工作流
        if (baseline.requiresWorkflow() && !candidate.requiresWorkflow()) {
            return fallback(candidate, "workflow_boundary_violation");
        }
        return new RoutePlan(candidate, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, FULL_AUDIT);
    }

    /**
     * 确定性兜底：取原候选 intent 基线并继承其 ambiguous（secondary 丢弃）；null 候选→general_chat 基线。
     * source=DETERMINISTIC_FALLBACK, conf=0.75。
     */
    private RoutePlan fallback(RoutePlanCandidate original, String reason) {
        String intent = (original != null) ? original.intent() : "general_chat";
        RoutePlanCandidate baseline = baselines.baselineFor(intent);
        if (baseline == null) {
            baseline = baselines.baselineFor("general_chat");
        }
        boolean ambiguous = (original != null) && original.ambiguous();
        return new RoutePlan(baseline.withAmbiguous(ambiguous), RoutePlan.Source.DETERMINISTIC_FALLBACK, 0.75, List.of(reason));
    }
}
