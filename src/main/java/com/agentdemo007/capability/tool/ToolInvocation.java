package com.agentdemo007.capability.tool;

/**
 * {@code ResilientToolExecutor#invoke} 单工具调用产出（韧性应对后的收口，§5.14）。
 *
 * <p>成功 → {@code content} 为工具输出、{@code error} 为 null；
 * 失败 → {@code content} 为结构化错误 JSON（{@link ToolError#toText}）、{@code error} 携带
 * 分类/根因/尝试次数。失败不抛出——错误结果回喂 LLM（Agent loop 自纠正/终答如实说明），
 * 系统不吞异常也不放任异常炸穿步骤。
 */
public record ToolInvocation(String content, ToolError error) {

    public boolean isError() {
        return error != null;
    }
}
