package com.agentdemo007.session.cache;

import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话消息 JSON 编解码测试（Phase 3·Redis 序列化逻辑，无需真实 Redis）。
 *
 * <p>{@link ChatMessageCodec} 负责 {@link ChatMessage}（sealed 四态）↔ JSON 的可逆映射，
 * 是 {@code RedisSessionCacheStore} 的纯序列化内核——可独立单测，不依赖 Redis 连接。
 * 收口：序列化为 Redis 存储格式（role+content DTO），不在 {@link ChatMessage} 上挂 Jackson 注解，
 * 流水线强类型保持库无关。
 */
class ChatMessageCodecTest {

    private final ChatMessageCodec codec = new ChatMessageCodec(new ObjectMapper());

    @Test
    void encodeDecode_roundTripsAllMessageTypes() {
        List<ChatMessage> original = List.of(
                new ChatMessage.System("sys"),
                new ChatMessage.User("u"),
                new ChatMessage.Ai("a"),
                new ChatMessage.ToolResult("tr")
        );

        List<ChatMessage> decoded = codec.decode(codec.encode(original));

        assertThat(decoded).hasSize(4);
        assertThat(decoded.get(0)).isInstanceOf(ChatMessage.System.class);
        assertThat(decoded.get(0).content()).isEqualTo("sys");
        assertThat(decoded.get(1)).isInstanceOf(ChatMessage.User.class);
        assertThat(decoded.get(1).content()).isEqualTo("u");
        assertThat(decoded.get(2)).isInstanceOf(ChatMessage.Ai.class);
        assertThat(decoded.get(2).content()).isEqualTo("a");
        assertThat(decoded.get(3)).isInstanceOf(ChatMessage.ToolResult.class);
        assertThat(decoded.get(3).content()).isEqualTo("tr");
    }

    @Test
    void decode_nullOrBlank_returnsEmpty() {
        assertThat(codec.decode(null)).isEmpty();
        assertThat(codec.decode("")).isEmpty();
        assertThat(codec.decode("   ")).isEmpty();
    }

    @Test
    void encode_emptyList_producesEmptyJsonArray() {
        String json = codec.encode(List.of());
        assertThat(json).isEqualTo("[]");
        assertThat(codec.decode(json)).isEmpty();
    }

    @Test
    void encode_preservesContentWithSpecialChars() {
        List<ChatMessage> original = List.of(new ChatMessage.User("你好，世界！\n换行 \"引号\" & 特殊"));

        List<ChatMessage> decoded = codec.decode(codec.encode(original));

        assertThat(decoded.get(0).content()).isEqualTo("你好，世界！\n换行 \"引号\" & 特殊");
    }

    @Test
    void decode_corruptJson_throwsIllegalState() {
        assertThatThrownBy(() -> codec.decode("{not json"))
                .isInstanceOf(IllegalStateException.class);
    }
}
