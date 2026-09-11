package com.agentdemo007.resilience;

/**
 * 可重试动作（{@link ResilientExecutor#execute} 的入参）。
 *
 * <p>函数式接口，{@code call()} 可抛任意 RuntimeException——由 {@link ExceptionTriage} 分诊决定
 * 重试 / 立即失败 / 反馈 LLM / 审计失败。收口：重试模板只认此强类型，不认各业务 lambda 的原生形态。
 */
@FunctionalInterface
public interface RetryAction<T> {

    T call();
}
