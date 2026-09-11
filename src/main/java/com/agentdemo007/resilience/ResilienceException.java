package com.agentdemo007.resilience;

/**
 * 韧性层异常基类（Phase 5）。
 *
 * <p>四个标记子类（{@link TransientException}/{@link NonRetryableException}/
 * {@link ToolRecoverableException}/{@link FatalException}）是引擎无关的分诊契约：
 * 未来的 LangChain4j 适配器把引擎原生异常（HTTP 429/403 等）翻译为这些标记，
 * {@link ExceptionTriage} 据此分诊——与 Phase 4 {@code ModelExecutor} 边界同哲学，不耦合具体引擎。
 */
public class ResilienceException extends RuntimeException {

    public ResilienceException(String message) {
        super(message);
    }

    public ResilienceException(String message, Throwable cause) {
        super(message, cause);
    }
}
