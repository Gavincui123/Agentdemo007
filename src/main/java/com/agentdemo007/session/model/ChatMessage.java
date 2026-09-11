package com.agentdemo007.session.model;

/**
 * 标准 LLM 消息格式（收口：会话历史强类型载体）。
 *
 * <p>四态 sealed 类型 system/user/ai/tool_result，作为 {@code PipelineContext.history}
 * 的元素类型——会话历史的唯一数据真相源（第四原则·状态收口）。
 * 禁止各步私造消息结构互相传参；新增消息形态在此扩展。
 *
 * <p>引擎无关：Phase 4 统一模型网关在此边界适配 LangChain4j 的
 * {@code dev.langchain4j.data.message.ChatMessage}，换引擎不换收口类型。
 */
public sealed interface ChatMessage permits ChatMessage.System, ChatMessage.User, ChatMessage.Ai, ChatMessage.ToolResult {

    /** 文本内容。 */
    String content();

    /** 系统提示词。 */
    record System(String content) implements ChatMessage {
    }

    /** 用户消息。 */
    record User(String content) implements ChatMessage {
    }

    /** 模型回复。 */
    record Ai(String content) implements ChatMessage {
    }

    /** 工具执行结果回传。 */
    record ToolResult(String content) implements ChatMessage {
    }

    /** 用户消息便捷工厂。 */
    static User user(String content) {
        return new User(content);
    }
}
