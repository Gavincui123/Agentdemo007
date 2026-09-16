package com.agentdemo007.capability.plan;

import java.util.Optional;

/**
 * 路由规划混合层（#133·rule→LLM→rule兜底，[[routeplan-design]]）。
 *
 * <p>三段混合（[[degradation-and-eval-principles]] 话术短路 + 每步降级 + 统一收口）：
 * <ol>
 *   <li><b>rule 短路</b>——fallbackIntent 的确定性基线无能力（general_chat /
 *       security_request / degradation_request：无 RAG/工具/工作流）→ 直接确定性基线，
 *       零 LLM（话术短路，省一次决策调用；security 直达 TRANSFER_TO_HUMAN，注入零 LLM）。</li>
 *   <li><b>LLM</b>——非短路意图 → {@link RouteCandidateSource#decide} 调 route_model（关思考）
 *       产 8 字段候选。未知 fallbackIntent 也不短路（rule 无定论 → 升级 LLM 救回）。</li>
 *   <li><b>rule 收敛/兜底</b>——候选经 {@link RoutePlanRuleMatcher#converge} 收敛
 *       （5 政策约束：采纳 source=LLM_WITH_POLICY_CONSTRAINTS/conf=0.9，或违例兜底到候选 intent 基线）；
 *       LLM 不可用（source empty）→ 确定性兜底到 <b>fallbackIntent</b> 基线（非 general_chat——
 *       IntentRecognition 已给可用意图；未知 fallbackIntent 则 general_chat）。路由永不崩。</li>
 * </ol>
 *
 * <p><b>fallbackIntent 来源</b>：IntentRecognitionStep 的 7 认知 intent 经 7→12 映射产出 12 业务意图
 * String（映射延后，[[routeplan-design]]；RoutePlanner 与 Intent 枚举解耦，只消费 String）。
 * LLM 挂时用它作基线查询键——IntentRecognition 与 route_model 独立，前者可给意图而后者不可用。
 *
 * <p>本类 plain class（非 @Component），seam 注入，装配归 #134 RoutePlanStep（@Bean 或 step 内构造）。
 * 引擎无关：LLM 步全在 {@link RouteCandidateSource} seam 后，换实现不改本类。
 */
public class RoutePlanner {

    private final RoutePlanRuleMatcher matcher;
    private final RoutePlanBaselines baselines;
    private final RouteCandidateSource source;

    public RoutePlanner(RoutePlanRuleMatcher matcher, RoutePlanBaselines baselines, RouteCandidateSource source) {
        this.matcher = matcher;
        this.baselines = baselines;
        this.source = source;
    }

    /**
     * 产 route_plan（混合三段）。
     *
     * @param fallbackIntent IntentRecognition 给的 12 业务意图 String（LLM 挂时的基线查询键；可未知）
     * @param prompt         预构的 route prompt（调方 #134 构造，含 8 字段规格 + tool_candidates + 历史）
     * @return 收敛后 route_plan（LLM 采纳 / 确定性兜底）
     */
    public RoutePlan plan(String fallbackIntent, String prompt) {
        RoutePlanCandidate fallbackBaseline = baselines.baselineFor(fallbackIntent);
        // 1. rule 短路：已知确定性意图（基线无能力）→ 零 LLM
        if (fallbackBaseline != null && isCapabilityEmpty(fallbackBaseline)) {
            return RoutePlan.deterministic(fallbackBaseline);
        }
        // 2. LLM：调 route_model 产候选（未知 fallbackIntent 不短路，交 LLM 救回）
        Optional<RoutePlanCandidate> candidate = source.decide(prompt);
        if (candidate.isEmpty()) {
            // 3a. rule 兜底：LLM 挂 → fallbackIntent 基线（未知则 general_chat）
            RoutePlanCandidate backstop = fallbackBaseline != null
                    ? fallbackBaseline
                    : baselines.baselineFor("general_chat");
            return RoutePlan.deterministic(backstop);
        }
        // 3b. rule 收敛：候选经白名单收敛（采纳或违例兜底到候选 intent 基线）
        return matcher.converge(candidate.get());
    }

    /** 基线无能力（无 RAG/工具/工作流）→ 确定性意图，无需 route_model 介入。 */
    private static boolean isCapabilityEmpty(RoutePlanCandidate c) {
        return !c.needsRag() && !c.needsBusinessTools() && !c.requiresWorkflow();
    }
}
