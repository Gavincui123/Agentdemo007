package com.agentdemo007.session.cache;

import com.agentdemo007.common.exception.SessionCacheException;
import com.agentdemo007.session.model.ChatMessage;

import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 会话缓存服务（第二层·会话缓存基础）。
 *
 * <p>收口会话记忆存取：load/append + TTL，底层依赖可插拔的 {@link SessionCacheStore}。
 * 任何存储异常统一封装为 {@link SessionCacheException}（保留 cause），供流水线会话步骤
 * 捕获后走 {@code SESSION_DOWN} 话术短路（§5.12 + eval/degradation.json deg-003），
 * 不向用户抛 5xx。
 *
 * <p>Phase 22（T98）：值结构升级为 {@link SessionMemory}（L1 原文 + L2 滚动摘要）——
 * {@link #load} 签名保持"纯消息列表"（存量调用方零改动，等价于 memory.messages()），
 * 新增 {@link #loadMemory}/ {@link #save} 供读侧窗口组装与终局异步压缩使用。
 *
 * <p><b>会话级互斥（2026-09-21 review 修订）</b>：Redis 后端的 append（读-改-写）与压缩写回
 * save（盲写）不是原子操作——append 的 get/set 横跨压缩 save 会用旧值覆盖压缩写回（丢摘要/
 * 丢压缩效果）。本类按 sessionId 条纹锁把同会话的 append 与 {@link #mergeSave}（锁内原子
 * load→merge→save）互斥收口，临界区微秒级、LLM 调用留锁外——零首字延迟影响。JVM 内完备；
 * 多实例部署为残余边界（需 WATCH/Lua/list 重构，demo 单实例不涉及）。
 */
public class SessionCacheService {

    /** 条纹锁段数：hash 碰撞只会让无关会话互斥（临界区微秒级，无害）。 */
    private static final int LOCK_STRIPES = 64;

    private final SessionCacheStore store;
    private final Duration defaultTtl;
    private final Object[] locks = new Object[LOCK_STRIPES];

    public SessionCacheService(SessionCacheStore store, Duration defaultTtl) {
        this.store = store;
        this.defaultTtl = defaultTtl;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    private Object lockFor(String sessionId) {
        if (sessionId == null) {
            return new Object(); // 防御：null 会话无共享写者，独立锁等价无锁
        }
        return locks[(sessionId.hashCode() & 0x7fffffff) % LOCK_STRIPES];
    }

    /** 加载会话记忆全量（含滚动摘要）；存储异常 → {@link SessionCacheException}。 */
    public SessionMemory loadMemory(String sessionId) {
        try {
            return store.load(sessionId);
        } catch (Exception e) {
            throw new SessionCacheException("会话缓存加载失败：" + e.getMessage(), e);
        }
    }

    /** 加载会话历史（存量口径：纯消息列表 = memory.messages()）；存储异常 → {@link SessionCacheException}。 */
    public List<ChatMessage> load(String sessionId) {
        return loadMemory(sessionId).messages();
    }

    /** 追加消息，使用默认 TTL（既有摘要原样保留）。 */
    public void append(String sessionId, List<ChatMessage> messages) {
        append(sessionId, messages, defaultTtl);
    }

    /** 追加消息并显式指定 TTL（同会话与 {@link #mergeSave} 互斥——Redis 读-改-写竞态收口）。 */
    public void append(String sessionId, List<ChatMessage> messages, Duration ttl) {
        synchronized (lockFor(sessionId)) {
            try {
                store.append(sessionId, messages, ttl);
            } catch (Exception e) {
                throw new SessionCacheException("会话缓存写入失败：" + e.getMessage(), e);
            }
        }
    }

    /** 压缩写回（终局异步路径专用）：整体替换记忆值（滚动摘要 + 裁剪后窗口）并刷新 TTL。 */
    public void save(String sessionId, SessionMemory memory) {
        save(sessionId, memory, defaultTtl);
    }

    /** 压缩写回（显式 TTL）。 */
    public void save(String sessionId, SessionMemory memory, Duration ttl) {
        try {
            store.save(sessionId, memory, ttl);
        } catch (Exception e) {
            throw new SessionCacheException("会话缓存写回失败：" + e.getMessage(), e);
        }
    }

    /**
     * 压缩写回的原子收口（2026-09-21 review 修订）：同会话条纹锁内执行
     * <b>load 最新值 → merger 合并（纯函数，不得出站调用）→ save</b>，
     * 与 {@link #append} 互斥——append 不再可能横跨写回用旧值覆盖（Redis 读-改-写竞态根治）。
     *
     * @param merger 纯合并函数：输入锁内读到的最新记忆，输出写回值（压缩侧在此拼回并发追加消息）
     * @return 实际写回的记忆
     */
    public SessionMemory mergeSave(String sessionId, UnaryOperator<SessionMemory> merger) {
        synchronized (lockFor(sessionId)) {
            SessionMemory merged = merger.apply(loadMemory(sessionId));
            save(sessionId, merged);
            return merged;
        }
    }
}
