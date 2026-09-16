package com.agentdemo007.capability.tool;

/**
 * 工具调用结构化结果（[[business-tools-workflow-dag]] §2.2·契约变更：替旧 {@code List<String>}）。
 *
 * <p>带工具名 + 通道 category，供 {@link ToolExecutionStep} 按 category 路由到 3 通道。
 * {@code content} = @Tool 返回的原始文本（LLM 消费格式）；RAG 通道 content 为
 * {@code {"text","source"}} JSON（由 {@link com.agentdemo007.capability.business.PolicyFragment#toJson} 产出）。
 *
 * @param name     工具名（@Tool spec name = 方法名）
 * @param content  工具返回文本
 * @param category 通道分类（{@link ToolSchemaProvider#categoryOf} 标注）
 */
public record ToolCallResult(String name, String content, ToolCategory category) {
}
