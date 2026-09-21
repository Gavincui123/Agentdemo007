package com.agentdemo007.session.cache;

import com.agentdemo007.session.model.ChatMessage;

import java.time.Duration;
import java.util.List;

/**
 * 会话记忆存储抽象（收口：存储位置可插拔；Phase 22 T98 值结构升级为 {@link SessionMemory}）。
 *
 * <p>生产实现 {@code RedisSessionCacheStore}（Redis）；测试用内存 fake。
 * 无论底层是 Redis 还是其它，{@link SessionCacheService} 只依赖本接口，
 * 保证会话记忆存取入口唯一、可替换。
 *
 * <p>Phase 22：{@code load} 返回 {@link SessionMemory}（L1 原文 + L2 滚动摘要），
 * 新增 {@code save}（终局异步压缩写回：替换摘要 + 裁剪后消息）。
 * {@code append} 语义不变（追加本轮 User/Ai，摘要原样保留）。
 */
public interface SessionCacheStore {

    /** 加载会话记忆（不存在返回空记忆；存量纯消息值 → 视为无摘要）。 */
    SessionMemory load(String sessionId);

    /** 追加消息并刷新 TTL（既有摘要原样保留）。 */
    void append(String sessionId, List<ChatMessage> messages, Duration ttl);

    /**
     * 压缩写回：整体替换该会话的记忆值（滚动摘要 + 裁剪后窗口）并刷新 TTL。
     * 仅终局异步压缩路径调用——与主链路 append 并发时，由压缩侧做增量合并防丢消息。
     */
    void save(String sessionId, SessionMemory memory, Duration ttl);
}
