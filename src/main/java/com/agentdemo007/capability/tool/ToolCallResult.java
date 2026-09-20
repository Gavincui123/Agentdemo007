package com.agentdemo007.capability.tool;

/**
 * 工具调用结构化结果（[[business-tools-workflow-dag]] §2.2·契约变更：替旧 {@code List<String>}）。
 *
 * <p>带工具名 + 通道 category，供 {@link ToolExecutionStep} 按 category 路由到 3 通道；
 * 失败结果（{@code error != null}）单独路由进 {@code context.toolErrors}（不混 runtimeFacts
 * 高置信事实），{@code content} 为结构化错误 JSON（{@link ToolError#toText}）。
 * {@code content} = @Tool 返回的原始文本（LLM 消费格式）；RAG 通道 content 为
 * {@code {"text","source"}} JSON（由 {@link com.agentdemo007.capability.business.PolicyFragment#toJson} 产出）。
 *
 * @param name     工具名（@Tool spec name = 方法名）
 * @param content  工具返回文本（失败时为结构化错误 JSON）
 * @param category 通道分类（{@link ToolSchemaProvider#categoryOf} 标注）
 * @param error    失败信息（分类/根因/尝试次数）；null=成功
 */
public record ToolCallResult(String name, String content, ToolCategory category, ToolError error) {

    /** 兼容构造：成功结果（无错误信息）。 */
    public ToolCallResult(String name, String content, ToolCategory category) {
        this(name, content, category, null);
    }

    /** 是否失败结果（错误通道路由判定）。 */
    public boolean isError() {
        return error != null;
    }
}
