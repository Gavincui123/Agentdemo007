package com.agentdemo007.resilience;

/**
 * 工具执行超时异常（Phase 9 工具韧性扩展）。
 *
 * <p>单次工具调用超过 {@code app.tool.timeout-ms} 预算时由 {@code ResilientToolExecutor}
 * 抛出（底层执行线程经 {@code FutureTask.cancel(true)} 硬中断，防外部系统 hang 死拖垮流水线）。
 * 分诊为瞬态可重试（退避重试）；重试耗尽 → 错误结果收口反馈 LLM（不吞异常）。
 */
public class ToolTimeoutException extends RuntimeException {

    public ToolTimeoutException(String message) {
        super(message);
    }
}
