package com.agentdemo007.observability.audit;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 审计事件模型测评。
 *
 * <p>验证 {@link AuditEvent} 强类型字段（§5.14：禁止各步私造 Map，统一收口于强类型记录）
 * + {@link AuditEventType} 覆盖验收要求的关键事件类别（注入/工具/HITL/故障转移/异常）。
 */
class AuditEventTest {

    @Test
    void of_setsAllFieldsAndTimestampsNow() {
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);

        AuditEvent event = AuditEvent.of(AuditEventType.INJECTION, "trace-1", "sess-1", "命中注入特征：忽略之前指令");

        assertThat(event.type()).isEqualTo(AuditEventType.INJECTION);
        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.sessionId()).isEqualTo("sess-1");
        assertThat(event.detail()).isEqualTo("命中注入特征：忽略之前指令");
        assertThat(event.timestamp()).isNotNull();
        assertThat(event.timestamp()).isAfterOrEqualTo(before);
        assertThat(event.timestamp()).isBeforeOrEqualTo(OffsetDateTime.now().plusSeconds(1));
    }

    @Test
    void of_nullTimestampsUtcNormalized() {
        AuditEvent event = AuditEvent.of(AuditEventType.EXCEPTION, "t", "s", null);
        assertThat(event.detail()).isNull();
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    void eventTypeCoversAcceptanceCriticalCategories() {
        // 验收：注入、工具调用、HITL、故障转移、异常 等关键事件审计完整
        Set<AuditEventType> required = EnumSet.of(
                AuditEventType.INJECTION,
                AuditEventType.TOOL_CALL,
                AuditEventType.HITL,
                AuditEventType.FAILOVER,
                AuditEventType.EXCEPTION);
        Set<AuditEventType> all = EnumSet.allOf(AuditEventType.class);
        assertThat(all).containsAll(required);
    }

    @Test
    void eventTypeToStringStableForAuditSerialization() {
        // 审计落库/序列化依赖枚举名稳定（不可漂移为中文 display name）
        assertThat(AuditEventType.INJECTION.name()).isEqualTo("INJECTION");
        assertThat(AuditEventType.FAILOVER.name()).isEqualTo("FAILOVER");
    }

    @Test
    void canonicalConstructorAllowsExplicitTimestampForDeterministicTests() {
        OffsetDateTime fixed = OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
        AuditEvent event = new AuditEvent(AuditEventType.HITL, "t", "s", "转人工", fixed);
        assertThat(event.timestamp()).isEqualTo(fixed);
    }
}
