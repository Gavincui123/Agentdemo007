package com.agentdemo007.session.cache;

import com.agentdemo007.session.model.ChatMessage;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话消息 JSON 编解码器（第二层·Redis 序列化内核）。
 *
 * <p>负责 {@link ChatMessage}（sealed 四态）↔ JSON 的可逆映射，是
 * {@code RedisSessionCacheStore} 的纯序列化内核——可独立单测，不依赖 Redis 连接。
 * 收口原则：序列化为 Redis 存储格式（role + content DTO），不在 {@link ChatMessage}
 * 上挂任何 Jackson 注解，流水线强类型保持库无关；Phase 4 网关在边界适配 LLM 引擎格式。
 *
 * <p>实现要点：使用 {@code ChatMessageDto(role, content)} 作为中性传输载体，避免 Jackson
 * 对 sealed 接口的 polymorphic 处理；decode 按 role 字符串重建对应的 record 子类型。
 */
public class ChatMessageCodec {

    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String ROLE_AI = "assistant";
    private static final String ROLE_TOOL = "tool_result";

    private final ObjectMapper mapper;

    public ChatMessageCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 编码：List<ChatMessage> → JSON 字符串。 */
    public String encode(List<ChatMessage> messages) {
        List<ChatMessageDto> dtos = new ArrayList<>();
        for (ChatMessage m : messages) {
            dtos.add(toDto(m));
        }
        try {
            return mapper.writeValueAsString(dtos);
        } catch (Exception e) {
            throw new IllegalStateException("会话消息编码失败：" + e.getMessage(), e);
        }
    }

    /** 解码：JSON 字符串 → List<ChatMessage>；null/空白 → 空列表。 */
    public List<ChatMessage> decode(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        List<ChatMessageDto> dtos;
        try {
            dtos = mapper.readValue(json, new TypeReference<List<ChatMessageDto>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("会话消息解码失败：" + e.getMessage(), e);
        }
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessageDto d : dtos) {
            result.add(fromDto(d));
        }
        return result;
    }

    private ChatMessageDto toDto(ChatMessage m) {
        if (m instanceof ChatMessage.System s) return new ChatMessageDto(ROLE_SYSTEM, s.content());
        if (m instanceof ChatMessage.User u) return new ChatMessageDto(ROLE_USER, u.content());
        if (m instanceof ChatMessage.Ai a) return new ChatMessageDto(ROLE_AI, a.content());
        if (m instanceof ChatMessage.ToolResult t) return new ChatMessageDto(ROLE_TOOL, t.content());
        throw new IllegalStateException("未知会话消息类型：" + m.getClass().getName());
    }

    private ChatMessage fromDto(ChatMessageDto d) {
        String role = (d.role() == null) ? "" : d.role();
        String content = (d.content() == null) ? "" : d.content();
        return switch (role) {
            case ROLE_SYSTEM -> new ChatMessage.System(content);
            case ROLE_USER -> new ChatMessage.User(content);
            case ROLE_AI -> new ChatMessage.Ai(content);
            case ROLE_TOOL -> new ChatMessage.ToolResult(content);
            default -> new ChatMessage.User(content); // 未知 role 归入用户消息，避免反序列化中断
        };
    }

    /** Redis 存储格式：中性角色 + 内容，不暴露 record 类型。 */
    private record ChatMessageDto(String role, String content) {}
}
