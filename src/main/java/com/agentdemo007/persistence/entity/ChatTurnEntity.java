package com.agentdemo007.persistence.entity;

import com.agentdemo007.persistence.mq.ChatTurnEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * 会话轮次持久化实体（Phase 13·异步落库）。
 *
 * <p>MQ 消费者 {@code HistoryPersistConsumer} 收到 {@link ChatTurnEvent} 后映射为本实体落库，
 * 对话完成后 DB 异步写入会话记录。强类型字段对齐 MQ 载荷（§5.14：禁止 Map，record→实体字段一一映射）。
 *
 * <p>非 {@code final}、protected 无参构造满足 JPA 代理要求；工厂 {@link #from(ChatTurnEvent)} 完成载荷映射。
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "chat_turn")
public class ChatTurnEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "raw_input", columnDefinition = "TEXT")
    private String rawInput;

    @Column(name = "final_reply", columnDefinition = "TEXT")
    private String finalReply;

    @Column(name = "intent", length = 32)
    private String intent;

    @Column(name = "degraded", nullable = false)
    private boolean degraded;

    @Column(name = "scenario", length = 64)
    private String scenario;

    @Column(name = "turn_timestamp", nullable = false)
    private OffsetDateTime timestamp;

    /** 落库时间戳（JPA 审计 @CreatedDate 自动填充；Instant 为时区无关绝对时刻，适配 Spring Data 审计支持类型，§5.11 不可篡改留存）。 */
    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 代理要求的无参构造。 */
    protected ChatTurnEntity() {
    }

    /** 工厂：从 MQ 载荷映射（消费者落库入口）。 */
    public static ChatTurnEntity from(ChatTurnEvent event) {
        ChatTurnEntity e = new ChatTurnEntity();
        e.traceId = event.traceId();
        e.sessionId = event.sessionId();
        e.rawInput = event.rawInput();
        e.finalReply = event.finalReply();
        e.intent = event.intent();
        e.degraded = event.degraded();
        e.scenario = event.scenario();
        e.timestamp = event.timestamp();
        return e;
    }

    public Long getId() {
        return id;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRawInput() {
        return rawInput;
    }

    public String getFinalReply() {
        return finalReply;
    }

    public String getIntent() {
        return intent;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public String getScenario() {
        return scenario;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
