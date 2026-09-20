package com.agentdemo007.capability.hitl;

import java.time.Instant;

/**
 * 人工工单（第四层·HITL 能力层内部强类型，不外泄到步骤间；仅 ticketId 经
 * {@code PipelineContext.hitlTicketId} 收口供下游/审计引用，§5.14）。
 *
 * <p>触发 HITL 后挂起的人工确认工单，状态流转 PENDING→{APPROVED, REJECTED, TIMEOUT}
 * （单向，{@code HumanTicketService#resolve} 状态机守卫）。人工审批（APPROVED/REJECTED）
 * 在带外异步发生；同步请求内工单恒为 PENDING，由 {@link HitlDecision} 据超时/权限判定
 * 短路为 {@code HITL_TIMEOUT} 话术。
 *
 * @param id              工单唯一标识
 * @param sessionId       会话标识
 * @param query           触发工单的用户问题
 * @param reason          触发原因
 * @param status          工单状态
 * @param createdAt       创建时间
 * @param resolvedAt      解析时间（未解析为 null）
 * @param idempotencyKey  业务幂等键（{@code hitl:{action}:{订单号}}，跨会话稳定；
 *                        2026-09-18 L2 配套。null=旧构造/无键，幂等与恢复锚定不适用）
 */
public record HumanTicket(String id, String sessionId, String query, String reason,
                          Status status, Instant createdAt, Instant resolvedAt,
                          String idempotencyKey) {

    public enum Status { PENDING, APPROVED, REJECTED, TIMEOUT }

    /** 兼容构造：无业务幂等键（旧调用点/测试零改动）。 */
    public HumanTicket(String id, String sessionId, String query, String reason,
                       Status status, Instant createdAt, Instant resolvedAt) {
        this(id, sessionId, query, reason, status, createdAt, resolvedAt, null);
    }
}
