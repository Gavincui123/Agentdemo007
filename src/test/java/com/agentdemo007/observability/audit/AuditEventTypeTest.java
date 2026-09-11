package com.agentdemo007.observability.audit;

import com.agentdemo007.common.degradation.DegradationScenario;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计事件类型映射测评（Phase 13·{@link AuditEventType#from(DegradationScenario)}）。
 *
 * <p>降级场景 → 审计类型是单一真相源映射（§5.14 收口）：流水线编排器短路/异常收口时经此映射
 * 决定审计事件类型，避免散落各处自行 if/else。映射规则稳定、append-only（§5.11）。
 */
class AuditEventTypeTest {

    @Test
    void from_mapsKeyEventScenariosToSpecificAuditTypes() {
        assertThat(AuditEventType.from(DegradationScenario.INJECTION)).isEqualTo(AuditEventType.INJECTION);
        assertThat(AuditEventType.from(DegradationScenario.TOOL_FAILURE)).isEqualTo(AuditEventType.TOOL_FAILURE);
        assertThat(AuditEventType.from(DegradationScenario.HITL_TIMEOUT)).isEqualTo(AuditEventType.HITL);
        assertThat(AuditEventType.from(DegradationScenario.FAILOVER_EXHAUSTED)).isEqualTo(AuditEventType.FAILOVER_EXHAUSTED);
        assertThat(AuditEventType.from(DegradationScenario.MODEL_DOWN)).isEqualTo(AuditEventType.MODEL_DOWN);
        assertThat(AuditEventType.from(DegradationScenario.SESSION_DOWN)).isEqualTo(AuditEventType.SESSION_DOWN);
        assertThat(AuditEventType.from(DegradationScenario.OUTPUT_FALLBACK)).isEqualTo(AuditEventType.OUTPUT_FALLBACK);
        assertThat(AuditEventType.from(DegradationScenario.RAG_SKIP)).isEqualTo(AuditEventType.RAG_SKIP);
    }

    @Test
    void from_mapsGenericDegradationScenariosToDegradationType() {
        // 无专属类型的降级场景（请求类/限流/未知意图）统一收口到 DEGRADATION
        assertThat(AuditEventType.from(DegradationScenario.BAD_REQUEST)).isEqualTo(AuditEventType.DEGRADATION);
        assertThat(AuditEventType.from(DegradationScenario.PAYLOAD_TOO_LARGE)).isEqualTo(AuditEventType.DEGRADATION);
        assertThat(AuditEventType.from(DegradationScenario.RATE_LIMITED)).isEqualTo(AuditEventType.DEGRADATION);
        assertThat(AuditEventType.from(DegradationScenario.UNKNOWN_INTENT)).isEqualTo(AuditEventType.DEGRADATION);
    }

    @Test
    void from_mapsInternalScenarioToExceptionType() {
        // INTERNAL 收口（步骤异常全局兜底）→ EXCEPTION 审计类型
        assertThat(AuditEventType.from(DegradationScenario.INTERNAL)).isEqualTo(AuditEventType.EXCEPTION);
    }
}
