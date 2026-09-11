package com.agentdemo007.persistence.entity;

import com.agentdemo007.observability.audit.AuditEvent;
import com.agentdemo007.observability.audit.AuditEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * 审计事件持久化实体（Phase 13·独立审计链路）。
 *
 * <p>MQ 消费者 {@code AuditConsumer} 收到 {@link AuditEvent} 后映射为本实体落库——
 * 注入、工具调用、HITL、故障转移、异常等关键事件审计库有完整记录（§5.11 可审计性）。
 * 强类型字段对齐审计记录（§5.14：禁止 Map）；{@code type} 枚举存名（稳定序列化标识，append-only）。
 *
 * <p>非 {@code final}、protected 无参构造满足 JPA 代理要求；工厂 {@link #from(AuditEvent)} 完成载荷映射。
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "audit_event")
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private AuditEventType type;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "event_timestamp", nullable = false)
    private OffsetDateTime timestamp;

    /** 落库时间戳（JPA 审计 @CreatedDate 自动填充；Instant 为时区无关绝对时刻，适配 Spring Data 审计支持类型，§5.11 不可篡改留存）。 */
    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 代理要求的无参构造。 */
    protected AuditEventEntity() {
    }

    /** 工厂：从审计事件映射（消费者落库入口）。 */
    public static AuditEventEntity from(AuditEvent event) {
        AuditEventEntity e = new AuditEventEntity();
        e.type = event.type();
        e.traceId = event.traceId();
        e.sessionId = event.sessionId();
        e.detail = event.detail();
        e.timestamp = event.timestamp();
        return e;
    }

    public Long getId() {
        return id;
    }

    public AuditEventType getType() {
        return type;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getDetail() {
        return detail;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
