package com.agentdemo007.output;

import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.degradation.DegradationScenario;

import java.util.Optional;

/**
 * 结构化输出网关（第七层·LLM 原始输出→结构化校验入口）。
 *
 * <p>对 LLM 原始输出按 {@link OutputSchema} 校验（{@link JsonSchemaValidator}），
 * 失败经 {@link OutputRetryFallback} 重试/兜底（§5.7 + §5.12 结构化输出行）：
 * <ul>
 *   <li>校验通过（或重问修正后通过）→ 原文输出，{@code degraded=false}；</li>
 *   <li>重试耗尽 / 无重问能力（dev）→ {@code OUTPUT_FALLBACK} 兜底话术，{@code degraded=true}。</li>
 * </ul>
 * 兜底话术经 {@link DegradationPhraseCenter} 取（支持 Nacos 热更新覆盖）。
 */
public class StructuredOutputGateway {

    private final OutputRetryFallback retryFallback;
    private final DegradationPhraseCenter phraseCenter;

    public StructuredOutputGateway(OutputRetryFallback retryFallback, DegradationPhraseCenter phraseCenter) {
        this.retryFallback = retryFallback;
        this.phraseCenter = phraseCenter;
    }

    /**
     * @param rawOutput LLM 原始输出
     * @param schema    输出结构描述（{@code null}=对话模式，宽松通过）
     * @param reAsk     重问 seam（dev {@link ReAsk#none()}）
     * @return 校验通过的原文，或兜底话术
     */
    public OutputResult process(String rawOutput, OutputSchema schema, ReAsk reAsk) {
        Optional<String> validated = retryFallback.process(rawOutput, schema, reAsk);
        if (validated.isPresent()) {
            return new OutputResult(validated.get(), false);
        }
        return new OutputResult(phraseCenter.phrase(DegradationScenario.OUTPUT_FALLBACK), true);
    }
}
