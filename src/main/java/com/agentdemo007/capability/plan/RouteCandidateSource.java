package com.agentdemo007.capability.plan;

import java.util.Optional;

/**
 * route_model 候选来源 seam（#133·[[routeplan-design]] LLM 步的引擎无关边界）。
 *
 * <p>实现负责：构造 route prompt（8 字段 JSON 规格 + tool_candidates + 4 知识域）→
 * 经 {@code ChatLlmService.decide(prompt)}（关思考，[[phase-llm-primary-backup-breaker]]）→
 * 解析模型输出为 {@link RoutePlanCandidate}。任一环失败（模型不可用 / 输出不可解析）→
 * 返回 {@link Optional#empty()}，交 {@link RoutePlanner} 走 rule 兜底（路由永不崩）。
 *
 * <p>seam 意图：{@link RoutePlanner} 混合流程与「prompt 构造 + decide + JSON 解析」解耦——
 * 混合层先以假 source 跑通（[[dont-hardwrite-use-dep-methods]] 铁律②），真实现后置 Slice 2
 * 接 {@code ChatLlmService} + Jackson。prompt 由调用方（RoutePlanStep #134 或 prompt 构造器）
 * 预构后传入，本 seam 不承担 prompt 构造（单一职责）。
 */
public interface RouteCandidateSource {

    /**
     * 调 route_model 产候选。
     *
     * @param prompt 预构的 route prompt（8 字段规格 + tool_candidates + 历史 + 用户问题）
     * @return 解析成功的候选；模型不可用或输出不可解析则 empty
     */
    Optional<RoutePlanCandidate> decide(String prompt);
}
