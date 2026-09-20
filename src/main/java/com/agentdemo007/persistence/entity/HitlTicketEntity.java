package com.agentdemo007.persistence.entity;

import com.agentdemo007.capability.hitl.HumanTicket;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 人工工单持久化实体（L2 挂起-恢复·2026-09-18 用户裁决：工单 DB 持久化）。
 *
 * <p>内存 {@code HumanTicketService} 为状态机真相源（单实例），本表为<b>持久副本</b>：
 * 建单/决议均经单守护线程 {@code hitl-ticket-writer} <b>异步落库</b>（不影响主线），重启后
 * 按 id / 幂等键 / PENDING 列表回源重建（审批在重启后仍可继续，不再"重启丢工单"）。
 *
 * <p>列对齐 {@link HumanTicket}（{@code status} 存枚举名，append-only）；幂等键索引支撑
 * 跨会话锚定查询（{@code findByIdempotencyKeyOrderByCreatedAtDesc} 取最新单——TIMEOUT
 * 重建后键指向新单的语义与内存一致）。非 {@code final}、protected 无参构造满足 JPA 代理要求。
 */
@Entity
@Table(name = "hitl_ticket",
        indexes = {
                @Index(name = "idx_hitl_ticket_key", columnList = "idempotency_key"),
                @Index(name = "idx_hitl_ticket_status", columnList = "status")
        })
public class HitlTicketEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 工单唯一标识（内存 UUID）。 */
    @Column(name = "ticket_id", nullable = false, unique = true, length = 64)
    private String ticketId;

    /** 业务幂等键（hitl:{action}:{订单号}，可空=无锚点旧单）。 */
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "query", columnDefinition = "TEXT")
    private String query;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** 状态机（存名，稳定序列化标识）：PENDING → {APPROVED, REJECTED, TIMEOUT} 单向。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private HumanTicket.Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 决议时刻（null=未决议）。 */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** JPA 代理要求的无参构造。 */
    protected HitlTicketEntity() {
    }

    /** 工厂：从内存工单映射（建单/决议后异步落库共用）。 */
    public static HitlTicketEntity from(HumanTicket ticket) {
        HitlTicketEntity e = new HitlTicketEntity();
        e.ticketId = ticket.id();
        e.idempotencyKey = ticket.idempotencyKey();
        e.sessionId = ticket.sessionId();
        e.query = ticket.query();
        e.reason = ticket.reason();
        e.status = ticket.status();
        e.createdAt = ticket.createdAt();
        e.resolvedAt = ticket.resolvedAt();
        return e;
    }

    /** 决议后同步终态（落库行已存在时复用，不另起新行）。 */
    public void sync(HumanTicket ticket) {
        this.status = ticket.status();
        this.resolvedAt = ticket.resolvedAt();
    }

    // ---- 读写访问器 ----

    public Long getId() {
        return id;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getQuery() {
        return query;
    }

    public String getReason() {
        return reason;
    }

    public HumanTicket.Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
