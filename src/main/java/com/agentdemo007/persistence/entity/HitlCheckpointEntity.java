package com.agentdemo007.persistence.entity;

import com.agentdemo007.capability.hitl.HitlCheckpointSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * HITL 挂起检查点持久化实体（L2 挂起-恢复·2026-09-18）。
 *
 * <p>StateGraph 断点保存的落库形态：HitlStep(610) 挂起即异步写入（{@code hitl_checkpoint} 表，
 * JPA {@code ddl-auto=update} 自动建表，dev H2 / prod MySQL），审批通过后
 * {@code HitlResumeService} 读此快照恢复执行。内存索引为主读路径（§5.12 降级：DB 抖动不阻塞
 * 挂起/恢复），本表为<b>持久副本</b>（重启后可审计追溯；工单本体仍内存实现，重启丢失时
 * 快照保留但不具恢复入口——如实降级，不伪造恢复）。
 *
 * <p>状态机：ACTIVE（挂起待审批）→ CONSUMED（已消费恢复，防重复 resume）/ EXPIRED
 * （漂移校验失败作废）。非 {@code final}、protected 无参构造满足 JPA 代理要求；
 * 工厂 {@link #from(HitlCheckpointSnapshot, String)} 完成载荷映射。
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "hitl_checkpoint",
        indexes = {
                @Index(name = "idx_hitl_checkpoint_ticket", columnList = "ticket_id"),
                @Index(name = "idx_hitl_checkpoint_key", columnList = "idempotency_key")
        })
public class HitlCheckpointEntity {

    public enum Status { ACTIVE, CONSUMED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 挂起工单 id（一对一关联 checkpoint；unique）。 */
    @Column(name = "ticket_id", nullable = false, unique = true, length = 64)
    private String ticketId;

    /** 业务幂等键（hitl:{action}:{订单号}，跨会话稳定；漂移校验锚点）。 */
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    /** 挂起请求 traceId（血缘关联，恢复执行生成新 trace）。 */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    /** 状态机（存名，稳定序列化标识）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    /** 快照 JSON（HitlCheckpointSnapshot 序列化）。 */
    @Column(name = "snapshot_json", columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(name = "paused_at", nullable = false)
    private Instant pausedAt;

    /** 恢复执行时刻（null=未恢复）。 */
    @Column(name = "resumed_at")
    private Instant resumedAt;

    /** 恢复执行产出的最终回复（终态留痕）。 */
    @Column(name = "resume_reply", columnDefinition = "TEXT")
    private String resumeReply;

    /** 作废原因（漂移校验失败/重复消费被拒时填）。 */
    @Column(name = "expire_reason", length = 256)
    private String expireReason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 代理要求的无参构造。 */
    protected HitlCheckpointEntity() {
    }

    /** 工厂：从快照映射（ACTIVE 起点落库）。 */
    public static HitlCheckpointEntity from(HitlCheckpointSnapshot snapshot, String snapshotJson) {
        HitlCheckpointEntity e = new HitlCheckpointEntity();
        e.ticketId = snapshot.ticketId();
        e.idempotencyKey = snapshot.idempotencyKey();
        e.traceId = snapshot.traceId();
        e.sessionId = snapshot.sessionId();
        e.status = Status.ACTIVE;
        e.snapshotJson = snapshotJson;
        e.pausedAt = Instant.ofEpochMilli(snapshot.pausedAtMs());
        return e;
    }

    // ---- 读写访问器（JPA 实体惯例，非 record：有生命周期状态迁移）----

    public Long getId() {
        return id;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public Instant getResumedAt() {
        return resumedAt;
    }

    public void markResumed(Instant resumedAt, String resumeReply) {
        this.resumedAt = resumedAt;
        this.resumeReply = resumeReply;
    }

    public String getExpireReason() {
        return expireReason;
    }

    public void markExpired(String reason) {
        this.status = Status.EXPIRED;
        this.expireReason = reason;
    }
}
