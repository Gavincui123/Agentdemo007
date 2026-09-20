package com.agentdemo007.capability.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 售后工作流提交业务记忆（2026-09-18 用户裁决：决策层要有"衔接 vs 新意图"判定）。
 *
 * <p><b>为什么需要</b>：短期记忆（会话历史）此前只喂理解层（改写/意图/出答），@670 工作流状态机
 * 每轮只看"当前轮 routePlan + rawInput"——用户同一退款动作的第 4 轮"我要退款"被误判为新请求
 * 重新澄清、第 5 轮"ORD-001"重复提单。本登记表给决策层补上<b>业务动作记忆</b>：
 * 已提交的售后单按 <b>{action}:{orderId} 业务键</b>（跨会话稳定，镜像 HITL 幂等键裁决）登记；
 * 另附 sessionId → 最近提交键索引，支持"未带订单号的重复请求"按会话衔接判定。
 *
 * <p>判定语义（{@code WorkflowExecutionStep} 决策前置）：
 * <ul>
 *   <li>同 action 同订单（或同会话最近同 action 且本轮无单号）→ <b>衔接</b>：回复"已在处理中
 *       （售后单号 X）"，不重复澄清、不重复提单；</li>
 *   <li>同 action <b>不同订单</b> → 新业务实体：正常走图（新单新提）；</li>
 *   <li>TTL（默认 30 分钟）过期 → 视为可重新申请（demo 口径：审批结束后允许再次发起）。</li>
 * </ul>
 * 单实例内存实现（与工单/检查点服务同口径）；多实例/重启持久化迭代后补。
 */
public class WorkflowSubmissionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSubmissionRegistry.class);

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    /** 已受理提交条目：业务键 + 售后单号 + 登记时刻（TTL 判定用）。 */
    public record Entry(String action, String orderId, String workflowId, String sessionId, long registeredAtMs) {
        boolean expired(long ttlMs, long nowMs) {
            return (nowMs - registeredAtMs) > ttlMs;
        }
    }

    private final Map<String, Entry> byOrderKey = new ConcurrentHashMap<>();
    /** sessionId → 最近提交业务键（无单号轮次的会话级衔接判定索引；仅指向最新一笔）。 */
    private final Map<String, String> latestKeyBySession = new ConcurrentHashMap<>();
    private final long ttlMs;

    public WorkflowSubmissionRegistry() {
        this(DEFAULT_TTL.toMillis());
    }

    public WorkflowSubmissionRegistry(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /** 提交受理成功后登记（Approved/Timeout 均算已受理；Rejected 不登记，可修正后重试）。 */
    public Entry register(String action, String orderId, String workflowId, String sessionId) {
        if (action == null || action.isBlank() || orderId == null || orderId.isBlank()) {
            return null; // 无业务键不登记（不伪造锚点）
        }
        Entry e = new Entry(action.toUpperCase(java.util.Locale.ROOT), orderId,
                workflowId, sessionId, System.currentTimeMillis());
        String key = orderKey(e.action(), orderId);
        byOrderKey.put(key, e);
        if (sessionId != null && !sessionId.isBlank()) {
            latestKeyBySession.put(sessionId, key);
        }
        log.info("售后提交登记：action={} orderId={} workflowId={} sessionId={}", // 审计
                e.action(), orderId, workflowId, sessionId);
        return e;
    }

    /**
     * 查活跃提交（衔接判定）：订单号非空按业务键精确查；订单号缺失回退"会话最近一笔同 action 提交"。
     * TTL 过期条目惰性剔除并视为无（可重新申请）。
     */
    public Optional<Entry> findActive(String action, String orderId, String sessionId) {
        if (action == null || action.isBlank()) {
            return Optional.empty();
        }
        String normalized = action.toUpperCase(java.util.Locale.ROOT);
        long now = System.currentTimeMillis();
        if (orderId != null && !orderId.isBlank()) {
            return active(normalized, byOrderKey.get(orderKey(normalized, orderId)), now);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            String latestKey = latestKeyBySession.get(sessionId);
            if (latestKey != null) {
                return active(normalized, byOrderKey.get(latestKey), now);
            }
        }
        return Optional.empty();
    }

    /** 撤销登记（仲裁 WITHDRAW 语义·2026-09-18）：移除业务键，并移除指向它的会话最近键指针。 */
    public boolean withdraw(String action, String orderId, String sessionId) {
        if (action == null || action.isBlank() || orderId == null || orderId.isBlank()) {
            return false;
        }
        String normalized = action.toUpperCase(java.util.Locale.ROOT);
        String key = orderKey(normalized, orderId);
        Entry removed = byOrderKey.remove(key);
        if (removed == null) {
            return false;
        }
        if (sessionId != null && !sessionId.isBlank()) {
            latestKeyBySession.remove(sessionId, key); // 仅当指向被撤键时移除（两参 remove）
        }
        log.info("售后提交撤销：action={} orderId={} workflowId={} sessionId={}", // 审计
                normalized, orderId, removed.workflowId(), sessionId);
        return true;
    }

    private Optional<Entry> active(String action, Entry e, long nowMs) {
        if (e == null) {
            return Optional.empty();
        }
        if (e.expired(ttlMs, nowMs)) {
            byOrderKey.remove(orderKey(e.action(), e.orderId()), e); // 惰性过期（幂等移除）
            return Optional.empty();
        }
        return e.action().equals(action) ? Optional.of(e) : Optional.empty();
    }

    private static String orderKey(String action, String orderId) {
        return action + ":" + orderId;
    }
}
