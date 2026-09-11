package com.agentdemo007.common.pipeline;

import com.agentdemo007.common.degradation.DegradationScenario;

import java.util.List;

/**
 * 终端收口：流水线执行后的对外统一结果。
 *
 * <p>由 {@code ChatController}（Phase 12）翻译为 {@link com.agentdemo007.common.response.UnifiedResponse}。
 * 任何步骤都不直接写 HTTP——只有终端把 {@code PipelineResult} 映射为对外响应（第四原则·对外收口）。
 *
 * @param reply     最终回复内容（正常回复或话术）
 * @param degraded  是否经历了短路/降级
 * @param scenario  降级场景名（无降级为 {@code null}）
 * @param citations RAG 命中片段的可追溯引用（来源+摘要串，Phase 20 citation）。正常/降级回复携带，
 *                  话术短路为空。<b>可追溯 ≠ 一定正确</b>：仅标示信息出处，不背书回复绝对正确。
 */
public record PipelineResult(String reply, boolean degraded, String scenario, List<String> citations) {

    /** 正常完成（无来源标注，兼容既有桩/测试）。 */
    public static PipelineResult ok(String reply) {
        return new PipelineResult(reply, false, null, List.of());
    }

    /** 正常完成 + RAG 命中来源（Phase 20 citation，回答可追溯）。 */
    public static PipelineResult ok(String reply, List<String> citations) {
        return new PipelineResult(reply, false, null, citations != null ? citations : List.of());
    }

    /** 话术短路终止（零 LLM，无来源标注——话术不背书出处）。 */
    public static PipelineResult shortCircuit(String phrase, DegradationScenario scenario) {
        return new PipelineResult(phrase, true, scenario.name(), List.of());
    }

    /** 降级完成（有降级但仍产出回复，无来源标注，兼容既有桩/测试）。 */
    public static PipelineResult degraded(String reply, DegradationScenario scenario) {
        return new PipelineResult(reply, true, scenario.name(), List.of());
    }

    /** 降级完成 + RAG 命中来源（Phase 20 citation，降级回复仍可追溯）。 */
    public static PipelineResult degraded(String reply, DegradationScenario scenario, List<String> citations) {
        return new PipelineResult(reply, true, scenario.name(), citations != null ? citations : List.of());
    }
}
