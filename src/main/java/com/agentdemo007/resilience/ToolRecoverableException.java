package com.agentdemo007.resilience;

/**
 * 工具可恢复异常（参数错误、非法表达式、除零等）——完整异常内容反馈 LLM 自纠正。
 *
 * <p>由 {@link ToolErrorFeedback} 封装为结构化反馈 prompt，受最大迭代次数限制（§5.8）。
 * 工具执行入口（Phase 9 {@code ToolExecutor}）将工具异常包装为本类型。
 */
public class ToolRecoverableException extends ResilienceException {

    public ToolRecoverableException(String message) {
        super(message);
    }

    public ToolRecoverableException(String message, Throwable cause) {
        super(message, cause);
    }
}
