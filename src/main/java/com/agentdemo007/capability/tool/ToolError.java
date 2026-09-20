package com.agentdemo007.capability.tool;

/**
 * 工具调用失败结构化信息（§5.14 强类型收口）：分类 + 根因消息 + 实际尝试次数。
 *
 * <p>经 {@link ToolCallResult#error()} 随结果流转，{@code ToolExecutionStep} 据此把失败结果
 * 路由进 {@code context.toolErrors}（不混 runtimeFacts 高置信事实）；{@link #toText(String)}
 * 产出结构化 JSON 文本——回喂探测 LLM（Agent loop 自纠正/澄清）与注入终答上下文
 * （异常信息交 LLM 做策略/回复客户，系统不吞异常）共用同一份事实。
 *
 * @param kind     失败分类
 * @param message  根因消息（根因异常 message，缺省异常类名）
 * @param attempts 实际执行尝试次数（含首次；UNKNOWN_TOOL=0 未执行）
 */
public record ToolError(ToolErrorKind kind, String message, int attempts) {

    /**
     * 结构化错误 JSON（喂 LLM 的事实格式）：{@code {"toolError":true,"tool":…,"kind":…,"message":…,"attempts":…}}。
     * 消息做最小 JSON 转义（引号/反斜杠/控制符），不含堆栈（不向模型/客户泄漏内部细节）。
     */
    public String toText(String toolName) {
        return "{\"toolError\":true,\"tool\":\"" + esc(toolName)
                + "\",\"kind\":\"" + kind()
                + "\",\"message\":\"" + esc(message())
                + "\",\"attempts\":" + attempts() + "}";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
