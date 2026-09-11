package com.agentdemo007.observability.audit;

import java.time.OffsetDateTime;

/**
 * 审计事件结构体（Phase 13·独立审计链路载体）。
 *
 * <p>强类型 record（§5.14：禁止各步私造 Map，统一收口于强类型记录）：
 * 流水线内审计点经 {@code context.addAuditEvent(...)} 收集至此，
 * 由终端后置钩子 {@code ChatTurnFinalizer} 刷出到 {@code AuditProducer}（异步落库）。
 * 接入层注入等预流水线审计点直接经 {@code AuditProducer} 发布。
 *
 * <p>事件不可变、只增不改（§5.11 可审计性：注入/工具/HITL/故障转移/异常关键事件全量留痕）。
 *
 * @param type      事件类别
 * @param traceId   链路标识（经 {@code TraceId} 收口，跨 MQ 消费者贯通）
 * @param sessionId 会话标识（多轮追溯）
 * @param detail    事件明细（脱敏后的业务摘要，不含完整敏感载荷）
 * @param timestamp 事件发生时间（ISO-8601 偏移时间）
 */
public record AuditEvent(AuditEventType type, String traceId, String sessionId, String detail, OffsetDateTime timestamp) {

    /** 工厂：当前时间戳。流水线内审计点常用入口。 */
    public static AuditEvent of(AuditEventType type, String traceId, String sessionId, String detail) {
        return new AuditEvent(type, traceId, sessionId, detail, OffsetDateTime.now());
    }
}
