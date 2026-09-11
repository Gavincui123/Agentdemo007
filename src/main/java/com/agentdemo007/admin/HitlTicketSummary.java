package com.agentdemo007.admin;

import java.time.Instant;

/**
 * HITL 工单摘要 DTO（Phase 19·管理台对外收口）。
 *
 * <p>{@link com.agentdemo007.capability.hitl.HumanTicket} 的只读投影。{@code status} 映射为字符串稳定标识
 * （{@code PENDING}/{@code APPROVED}/{@code REJECTED}/{@code TIMEOUT}），{@code resolvedAt} 未解析为 null。
 */
public record HitlTicketSummary(String id, String sessionId, String query, String reason,
                               String status, Instant createdAt, Instant resolvedAt) {
}
