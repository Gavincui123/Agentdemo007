package com.agentdemo007.capability.plan;

import com.agentdemo007.gateway.llm.ChatLlmService;

import java.util.Optional;

/**
 * route_model 候选来源真实现（#133·Slice 2·{@link RouteCandidateSource} seam 落地）。
 *
 * <p>三步：{@link ChatLlmService#decide(String)}（关思考，经 gateway 主备容灾/熔断，
 * [[phase-llm-primary-backup-breaker]]）→ {@link RouteCandidateParser#parse}（snake_case JSON→候选）。
 * 任一环失败（模型不可用抛异常 / 返回 null / 输出不可解析）→ {@link Optional#empty()}，
 * 交 {@link RoutePlanner} 走 rule 兜底（路由永不崩，[[degradation-and-eval-principles]]）。
 *
 * <p>prompt 由调用方（RoutePlanStep #134 或 prompt 构造器）预构传入——本类只承担
 * decide + parse（单一职责）；route prompt 构造（8 字段规格 + tool_candidates + 4 域 + 历史）
 * 归调用方。出站 LLM 调用只经 {@link ChatLlmService}（§9.11 收口，与 IntentRecognizer/QueryRewriter 同源）。
 */
public class LlmRouteCandidateSource implements RouteCandidateSource {

    private final ChatLlmService llm;
    private final RouteCandidateParser parser;

    public LlmRouteCandidateSource(ChatLlmService llm, RouteCandidateParser parser) {
        this.llm = llm;
        this.parser = parser;
    }

    @Override
    public Optional<RoutePlanCandidate> decide(String prompt) {
        try {
            String raw = llm.decide(prompt, "路由计划");
            return parser.parse(raw); // parse 内含 null/blank/garbage/缺枚举守卫 → empty
        } catch (Exception e) {
            // route_model 不可用（主备耗尽/熔断）→ rule 兜底（路由永不崩）
            return Optional.empty();
        }
    }
}
