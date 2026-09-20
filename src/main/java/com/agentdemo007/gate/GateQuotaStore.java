package com.agentdemo007.gate;

/**
 * 闸口配额存储 seam（2026-09-18 用户裁决：发布为简历项目后配额必须防重启丢失）。
 *
 * <p>键已含自然日（{@code ip|yyyy-MM-dd}，Asia/Shanghai），存储只做原子计数——
 * 实现二选一：{@link InMemoryGateQuotaStore}（重启清零，dev/Redis 未启用）与
 * {@link RedisGateQuotaStore}（INCR + 48h TTL，重启不丢；{@code app.redis.enabled=true} 时装配）。
 * 计数须同步（闸口判定先于对话主链路，单次 Redis INCR 亚毫秒级，不构成主线阻塞）。
 */
public interface GateQuotaStore {

    /** 原子 +1 并返回增量后的值。 */
    int incrementAndGet(String key);

    /** 只读当前值（不消耗；缺键=0）。 */
    int peek(String key);
}
