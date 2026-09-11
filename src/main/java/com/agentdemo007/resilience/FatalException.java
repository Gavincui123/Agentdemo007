package com.agentdemo007.resilience;

/**
 * 致命异常（提示注入、权限不足、非法状态）——立即失败 + 审计留痕，绝不提交 LLM。
 *
 * <p>对应 §5.8 FATAL 行与原则①（话术短路）：接入层/流水线检测到注入或权限越界时，
 * 直接话术短路（INJECTION/权限话术），零 LLM。本类型用于在调用链中段被识别为致命的异常。
 */
public class FatalException extends ResilienceException {

    public FatalException(String message) {
        super(message);
    }

    public FatalException(String message, Throwable cause) {
        super(message, cause);
    }
}
