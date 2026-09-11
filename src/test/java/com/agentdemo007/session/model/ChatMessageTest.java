package com.agentdemo007.session.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标准 LLM 消息格式（收口：会话历史强类型载体）。
 *
 * <p>四态 system/user/ai/tool_result，作为 {@code PipelineContext.history} 的元素类型，
 * 是会话历史的唯一数据真相源（第四原则·状态收口）。Phase 4 网关在此边界适配 LangChain4j，
 * 换引擎不换收口类型。
 */
class ChatMessageTest {

    @Test
    void systemMessage_carriesContent() {
        ChatMessage m = new ChatMessage.System("你是一个助手");
        assertThat(m).isInstanceOf(ChatMessage.System.class);
        assertThat(m.content()).isEqualTo("你是一个助手");
    }

    @Test
    void userMessage_carriesContent() {
        assertThat(new ChatMessage.User("帮我查一下订单").content()).isEqualTo("帮我查一下订单");
    }

    @Test
    void aiMessage_carriesContent() {
        assertThat(new ChatMessage.Ai("好的，请提供订单号").content()).isEqualTo("好的，请提供订单号");
    }

    @Test
    void toolResultMessage_carriesContent() {
        assertThat(new ChatMessage.ToolResult("订单#123 已发货").content()).isEqualTo("订单#123 已发货");
    }

    @Test
    void allSubtypes_areChatMessageAndHaveContent() {
        ChatMessage[] all = {
                new ChatMessage.System("s"),
                new ChatMessage.User("u"),
                new ChatMessage.Ai("a"),
                new ChatMessage.ToolResult("t")
        };
        for (ChatMessage m : all) {
            assertThat(m).isInstanceOf(ChatMessage.class);
            assertThat(m.content()).isNotBlank();
        }
    }

    @Test
    void ofUser_isFactoryForUserMessage() {
        ChatMessage m = ChatMessage.user("你好");
        assertThat(m).isInstanceOf(ChatMessage.User.class);
        assertThat(m.content()).isEqualTo("你好");
    }
}
