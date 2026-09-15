package com.agentdemo007.capability.plan;

import java.util.List;

/**
 * RoutePlan 候选（第四层·route_model 结构化产出的 8 字段候选，参考系统 Pydantic 规格）。
 *
 * <p>模型经 {@code decide()}（关思考）产出 8 字段 JSON → 解析为本候选 →
 * {@link RoutePlanContractValidator} 4 跨字段约束校验 → 服务端白名单收敛 → 成 route_plan。
 * 校验不过 → {@code invalid_model_route_candidate} → 确定性兜底（路由环节永不崩溃）。
 *
 * <p>字段（详见 [[routeplan-design]]）：
 * <ol>
 *   <li>{@code intent} — 12 业务意图名（暂 String 占位，待 Intent 7→12 重构收紧为枚举）</li>
 *   <li>{@code needsRag} / {@code needsBusinessTools} / {@code requiresWorkflow} — 三 bool 替代能力 set</li>
 *   <li>{@code requiredTools} — 只能从 tool_candidates 选（白名单收敛）</li>
 *   <li>{@code knowledgeDomains} — 只能 4 域：faq / after_sale_policy / received_return_policy /
 *       promotion_and_member_policy</li>
 *   <li>{@code riskLevel} — low / medium / high</li>
 *   <li>{@code fallbackPolicy} — 6 值执行策略</li>
 * </ol>
 *
 * <p><b>串并行不在候选</b>（运行时 DAG 调度，按工具依赖元数据动态定，非路由决策）。
 */
public record RoutePlanCandidate(
        String intent,
        boolean needsRag,
        boolean needsBusinessTools,
        List<String> requiredTools,
        List<String> knowledgeDomains,
        RiskLevel riskLevel,
        boolean requiresWorkflow,
        FallbackPolicy fallbackPolicy,
        boolean ambiguous,          // 新：LLM 标本轮多意图/反复/否定矛盾→true
        String secondaryIntent      // 新：可空；仅「售后主意图+另有独立诉求」时填
) {
    /** 风险等级。HIGH 触发护栏覆盖（资金/权限不漏进普通对话）。 */
    public enum RiskLevel { LOW, MEDIUM, HIGH }

    /** 执行策略（收敛后 route_plan 据此分发 + 兜底）。 */
    public enum FallbackPolicy {
        SAFE_DETERMINISTIC_PATH, ASK_ORDER_ID, KNOWLEDGE_ONLY,
        TOOL_FIRST, WORKFLOW_FIRST, TRANSFER_TO_HUMAN
    }

    public RoutePlanCandidate {
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        knowledgeDomains = knowledgeDomains == null ? List.of() : List.copyOf(knowledgeDomains);
    }

    /** 兼容构造器：8 参（旧调用点/测试零改动）→ ambiguous=false, secondaryIntent=null。 */
    public RoutePlanCandidate(String intent, boolean needsRag, boolean needsBusinessTools,
                              List<String> requiredTools, List<String> knowledgeDomains,
                              RiskLevel riskLevel, boolean requiresWorkflow, FallbackPolicy fallbackPolicy) {
        this(intent, needsRag, needsBusinessTools, requiredTools, knowledgeDomains,
                riskLevel, requiresWorkflow, fallbackPolicy, false, null);
    }

    /** converge 兜底穿透用：保留原候选的 ambiguous。 */
    public RoutePlanCandidate withAmbiguous(boolean value) {
        return new RoutePlanCandidate(intent, needsRag, needsBusinessTools, requiredTools,
                knowledgeDomains, riskLevel, requiresWorkflow, fallbackPolicy, value, secondaryIntent);
    }
}
