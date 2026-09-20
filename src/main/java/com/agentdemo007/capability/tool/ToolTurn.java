package com.agentdemo007.capability.tool;

import java.util.List;

/**
 * {@code ToolCallExecutor#execute} 工具环节产出（有界 Agent loop 收口，§5.14）。
 *
 * @param results   累计工具调用结果（多轮混合：成功 + 失败，失败带 {@link ToolCallResult#error()}）
 * @param loopReply 探测模型在工具环节（第 2 轮起）转出的文本回复（错误回喂后模型放弃自纠正的
 *                  澄清/策略话术）；null=无（首轮无 tool_calls 的正常对话、全成功即停、轮次耗尽）
 */
public record ToolTurn(List<ToolCallResult> results, String loopReply) {

    public static ToolTurn empty() {
        return new ToolTurn(List.of(), null);
    }

    /** 是否含失败结果（熔断记账/指标口径：本环节无任何失败才算成功）。 */
    public boolean hasError() {
        return results.stream().anyMatch(ToolCallResult::isError);
    }
}
