package com.agentdemo007.capability.tool;

import java.util.Map;

/**
 * 工具调用（第四层·{@link ParamParser} 解析产出）。
 *
 * <p>携带工具名 + 已解析参数，是 {@link ToolRegistry} 查询与 {@link SchemaValidator} 校验的输入。
 * 强类型载体（非 Map 传参），仅存在于能力层内部，不外泄到 {@code PipelineContext}
 * （工具步骤只把执行结果文本写入 {@code context.toolResults}，§5.14 收口）。
 */
public record ToolCall(String toolName, Map<String, Object> args) {
}
