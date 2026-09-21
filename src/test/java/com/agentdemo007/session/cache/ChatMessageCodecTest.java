package com.agentdemo007.session.cache;

import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话消息 JSON 编解码测试（Phase 3·Redis 序列化逻辑，无需真实 Redis；Phase 22 T98 值结构升级回归）。
 *
 * <p>{@link ChatMessageCodec} 负责 {@link ChatMessage}（sealed 四态）↔ JSON 的可逆映射，
 * 是 {@code RedisSessionCacheStore} 的纯序列化内核——可独立单测，不依赖 Redis 连接。
 * Phase 22：值结构升级为 {@link SessionMemory}（object 格式），<b>存量纯消息数组格式平滑兼容</b>——
 * decode 首字符分流，旧值视为无摘要，零迁移。
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
        assertThat(codec.decodeMemory(null).messages()).isEmpty();
    }

    @Test
    void encode_emptyList_producesObjectFormatWithNullSummary() {
        // Phase 22 新格式：恒 object（无摘要时 summary=null，不写空串）
        String json = codec.encode(List.of());
        assertThat(json).contains("\"messages\"");
        assertThat(codec.decode(json)).isEmpty();
    }

    @Test
    void encodeMemory_roundTripsSummaryAndMessages() {
        SessionMemory memory = new SessionMemory("此前在处理退款", List.of(
                new ChatMessage.User("问"), new ChatMessage.Ai("答")));

        SessionMemory decoded = codec.decodeMemory(codec.encodeMemory(memory));

        assertThat(decoded.summary()).isEqualTo("此前在处理退款");
        assertThat(decoded.messages()).hasSize(2);
        assertThat(decoded.messages().get(1).content()).isEqualTo("答");
    }

    @Test
    void decodeMemory_legacyArrayFormat_treatedAsNoSummary() {
        // T98 存量兼容核心钉：升级前写入的 `[{role,content},…]` 数组 → 无摘要记忆，消息零丢失
        String legacy = "[{\"role\":\"user\",\"content\":\"旧问\"},{\"role\":\"assistant\",\"content\":\"旧答\"}]";

        SessionMemory decoded = codec.decodeMemory(legacy);

        assertThat(decoded.hasSummary()).isFalse();
        assertThat(decoded.messages()).hasSize(2);
        assertThat(decoded.messages().get(0).content()).isEqualTo("旧问");
        assertThat(decoded.messages().get(1).content()).isEqualTo("旧答");
        // encode 后新格式可再解码（首个压缩周期自然补齐摘要）
        assertThat(codec.decodeMemory(codec.encodeMemory(decoded)).messages()).hasSize(2);
    }

    @Test
    void encodeMemory_blankSummaryStoredAsNull() {
        String json = codec.encodeMemory(new SessionMemory("  ", List.of(new ChatMessage.User("x"))));
        assertThat(codec.decodeMemory(json).hasSummary()).isFalse();
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
