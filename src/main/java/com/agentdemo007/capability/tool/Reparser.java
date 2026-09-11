package com.agentdemo007.capability.tool;

import java.util.Optional;

/**
 * 工具自纠正重解析器（第四层·LLM 自纠正 seam）。
 *
 * <p>当工具执行抛 {@link com.agentdemo007.resilience.ToolRecoverableException}（参数错误/非法表达式/除零）时，
 * {@code ToolErrorFeedback} 产出反馈 prompt；本 seam 将反馈 prompt 重新解析为修正后的 {@link ToolCall}，
 * 供 {@link ToolExecutor} 重试（受最大迭代次数限制，§5.8 TOOL_RECOVERABLE）。
 *
 * <p>prod 接 LangChain4j：以反馈 prompt 调 LLM 产出修正调用（随 LangChain4j 接入，当前延后）。
 * dev/无 LLM：用 {@link #NONE}，任何工具异常立即视为耗尽 → {@code TOOL_FAILURE} 话术短路（§5.12 工具行）。
 */
@FunctionalInterface
public interface Reparser {

    /** 将反馈 prompt 重解析为修正工具调用；返回 empty 表示无法自纠正（立即耗尽）。 */
    Optional<ToolCall> reparse(String feedbackPrompt);

    /** 默认无自纠正（无 LLM）：任何工具异常立即耗尽。 */
    Reparser NONE = prompt -> Optional.empty();
}
