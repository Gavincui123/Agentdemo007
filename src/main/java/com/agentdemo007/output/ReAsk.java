package com.agentdemo007.output;

import java.util.Optional;

/**
 * 重问 seam（第七层·结构化输出校验失败后重新请求 LLM 修正）。
 *
 * <p>校验失败时由 {@link OutputRetryFallback} 调用，传入上次输出与错误反馈，
 * 期望返回修正后的输出。prod 实现经 {@code ChatLlmService} 以错误反馈重新提示模型；
 * dev 落 {@link #none()}（无重问能力→失败即耗尽，交由兜底话术），使同步链路不依赖真实 LLM 重问。
 */
@FunctionalInterface
public interface ReAsk {

    /**
     * @param lastOutput 上次（非法）输出
     * @param error      校验失败原因
     * @return 修正后的输出；空表示无法/不再重问
     */
    Optional<String> reAsk(String lastOutput, String error);

    /** dev 默认：无重问能力（始终返回空）。 */
    static ReAsk none() {
        return (lastOutput, error) -> Optional.empty();
    }
}
