package com.agentdemo007.session.cache;

import com.agentdemo007.session.model.ChatMessage;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话消息 JSON 编解码器（第二层·Redis 序列化内核）。
 *
 * <p>Phase 22 升级：编码/解码对象从"纯消息数组"升级为 {@link SessionMemory}（summary + messages），
 * <b>存量格式平滑兼容零迁移</b>——decode 对旧格式（纯消息 JSON 数组）按"无摘要"口径解码：
 * <ul>
 *   <li>新格式：{@code {"summary":"…","messages":[{role,content},…]}}（object）；</li>
 *   <li>旧格式：{@code [{role,content},…]}（array → summary=null，首个压缩周期自然补齐）。</li>
 * </ul>
 * encode 恒写新格式（无摘要时 summary 字段为 null——不写空串，省空间且语义唯一）。
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

    /** 编码：List<ChatMessage> → JSON 字符串（新格式 object，summary=null）。 */
    public String encode(List<ChatMessage> messages) {
        return encodeMemory(SessionMemory.ofMessages(messages));
    }

    /** 编码：SessionMemory → JSON 字符串（Phase 22 新格式，恒 object）。 */
    public String encodeMemory(SessionMemory memory) {
        List<ChatMessageDto> dtos = new ArrayList<>();
        for (ChatMessage m : memory.messages()) {
            dtos.add(toDto(m));
        }
        try {
            return mapper.writeValueAsString(new MemoryDto(memory.summary(), dtos));
        } catch (Exception e) {
            throw new IllegalStateException("会话消息编码失败：" + e.getMessage(), e);
        }
    }

    /** 解码：JSON 字符串 → List<ChatMessage>（旧格式/新格式通吃，摘要丢弃——存量口径兼容）。 */
    public List<ChatMessage> decode(String json) {
        return decodeMemory(json).messages();
    }

    /**
     * 解码：JSON 字符串 → SessionMemory；null/空白 → 空记忆。
     * 首字符分流：'['=旧格式纯消息数组（无摘要），'{'=新格式 SessionMemory——
     * 存量缓存值平滑升级，不迁移、不丢历史。
     */
    public SessionMemory decodeMemory(String json) {
        if (json == null || json.isBlank()) {
            return SessionMemory.empty();
        }
        String trimmed = json.trim();
        try {
            if (trimmed.charAt(0) == '[') {
                return SessionMemory.ofMessages(readMessages(trimmed)); // 存量格式：视为无摘要
            }
            MemoryDto dto = mapper.readValue(trimmed, new TypeReference<MemoryDto>() {});
            List<ChatMessage> messages = (dto.messages() != null)
                    ? fromDtos(dto.messages()) : new ArrayList<>();
            return new SessionMemory(blankToNull(dto.summary()), messages);
        } catch (Exception e) {
            throw new IllegalStateException("会话消息解码失败：" + e.getMessage(), e);
        }
    }

    private List<ChatMessage> readMessages(String json) {
        List<ChatMessageDto> dtos = mapper.readValue(json, new TypeReference<List<ChatMessageDto>>() {});
        return fromDtos(dtos);
    }

    private List<ChatMessage> fromDtos(List<ChatMessageDto> dtos) {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessageDto d : dtos) {
            result.add(fromDto(d));
        }
        return result;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
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

    /** Phase 22 存储值形状：滚动摘要 + 消息数组。 */
    private record MemoryDto(String summary, List<ChatMessageDto> messages) {}
}
