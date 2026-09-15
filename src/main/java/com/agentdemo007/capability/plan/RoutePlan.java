package com.agentdemo007.capability.plan;

import java.util.List;

/**
 * 收敛后的路由规划（第四层·RoutePlanRuleMatcher 产出，消费方=CapabilityStage/ToolExecutionStep/RagStep）。
 *
 * <p>包装收敛后的 {@link RoutePlanCandidate} + 来源/置信度/政策约束审计三件套：
 * <ul>
 *   <li>{@link Source#LLM_WITH_POLICY_CONSTRAINTS}——候选经白名单收敛采纳，conf=0.9；</li>
 *   <li>{@link Source#DETERMINISTIC_FALLBACK}——纯确定性兜底（route_model 不可用/候选 invalid），conf=0.75。</li>
 * </ul>
 *
 * <p><b>串并行不在 plan</b>（运行时 DAG，按工具依赖元数据动态定，归 CapabilityStage #135，
 * [[routeplan-design]]：无 mode/serialOrder）。本类只是<b>结果载体</b>，不含决策逻辑——
 * 谁产、经谁收敛、被谁覆盖，由 RoutePlanner/RoutePlanRuleMatcher 决定，审计经 RoutePlanStep 收口。
 *
 * <p>退役映射（② RoutePlan 回炉，[[routeplan-design]] "旧3字段RoutePlan+RoutePlanTest仍stale待清"）：
 * 旧 3 字段（capabilities/mode/serialOrder）+ Capability/Mode 枚举 + of/bothParallel/bothSerial/
 * needs(Capability) 已删——mode/serialOrder 移至 #135 运行时 DAG，能力不用 set 而拆
 * needs_rag/needs_business_tools/requires_workflow 三 bool（候选承载）。
 */
public record RoutePlan(
        RoutePlanCandidate candidate,
        Source source,
        double confidence,
        List<String> policyConstraints
) {

    /** route_plan 来源（source 字段，[[routeplan-design]]）。 */
    public enum Source { LLM_WITH_POLICY_CONSTRAINTS, DETERMINISTIC_FALLBACK }

    public RoutePlan {
        policyConstraints = policyConstraints == null ? List.of() : List.copyOf(policyConstraints);
    }

    /** 空规划（无能力，chit-chat / 纯出答）——三 bool 全 false。 */
    public boolean isEmpty() {
        return !candidate.needsRag() && !candidate.needsBusinessTools() && !candidate.requiresWorkflow();
    }

    // ---- 扁平访问器（delegate 到 candidate，消费方免 .candidate() 间接层）----

    public String intent() { return candidate.intent(); }
    public boolean needsRag() { return candidate.needsRag(); }
    public boolean needsBusinessTools() { return candidate.needsBusinessTools(); }
    public List<String> requiredTools() { return candidate.requiredTools(); }
    public List<String> knowledgeDomains() { return candidate.knowledgeDomains(); }
    public RoutePlanCandidate.RiskLevel riskLevel() { return candidate.riskLevel(); }
    public boolean requiresWorkflow() { return candidate.requiresWorkflow(); }
    public RoutePlanCandidate.FallbackPolicy fallbackPolicy() { return candidate.fallbackPolicy(); }
    public boolean ambiguous() { return candidate.ambiguous(); }
    public String secondaryIntent() { return candidate.secondaryIntent(); }

    /** 确定性兜底便捷工厂（纯基线，无候选收敛，source=DETERMINISTIC_FALLBACK，conf=0.75，无政策约束）。 */
    public static RoutePlan deterministic(RoutePlanCandidate baseline) {
        return new RoutePlan(baseline, Source.DETERMINISTIC_FALLBACK, 0.75, List.of());
    }
}
