package com.agentdemo007.session.cache;

import com.agentdemo007.session.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆值结构（Phase 22·T98 三层记忆升级）。
 *
 * <p>把存量"纯消息列表"升级为 {@code SessionMemory{summary, messages}}：
 * <ul>
 *   <li>{@code messages}——L1 原文层（最近原文，读侧按 token 预算窗口取尾）;</li>
 *   <li>{@code summary}——L2 滚动摘要（滑出窗口轮次的压缩表达，终局异步增量合并写回；
 *       null=尚无摘要，读侧降级为"无摘要多喂原文"，不等待不阻塞）。</li>
 * </ul>
 *
 * <p><b>存量兼容零迁移</b>：缓存中旧格式（纯消息 JSON 数组）由 {@link ChatMessageCodec}
 * 解码为 {@code summary=null} 的本结构——旧值视为"无摘要"，首个压缩周期自然补齐。
 * record 不可变（防御性拷贝），跨线程读安全（终局压缩异步写回的唯一写者经 Store 收口）。
 *
 * @param summary  L2 滚动摘要（≤200 字契约，由写侧保证）；null=无
 * @param messages L1 原文消息（User/Ai 轮次对；终局追加）
 */
public record SessionMemory(String summary, List<ChatMessage> messages) {

    public SessionMemory {
        messages = (messages != null) ? List.copyOf(messages) : List.of();
    }

    /** 空记忆（新会话）。 */
    public static SessionMemory empty() {
        return new SessionMemory(null, List.of());
    }

    /** 存量口径视图：纯消息列表 → 无摘要记忆（旧调用方/旧缓存值的平滑兼容）。 */
    public static SessionMemory ofMessages(List<ChatMessage> messages) {
        return new SessionMemory(null, messages);
    }

    /** 摘要是否可用（null/空白=无——读侧据此降级为多喂原文）。 */
    public boolean hasSummary() {
        return summary != null && !summary.isBlank();
    }

    /** 返回带新摘要的副本（原值不变）。 */
    public SessionMemory withSummary(String newSummary) {
        return new SessionMemory(newSummary, this.messages);
    }

    /** 返回替换消息列表的副本（压缩写回用；原值不变）。 */
    public SessionMemory withMessages(List<ChatMessage> newMessages) {
        return new SessionMemory(this.summary, newMessages);
    }

    /** 便捷：返回可变消息副本（Store append 拼接用）。 */
    public List<ChatMessage> mutableMessages() {
        return new ArrayList<>(messages);
    }
}
