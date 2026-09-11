package com.agentdemo007.common.pipeline;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.observability.audit.AuditEvent;
import com.agentdemo007.observability.audit.AuditEventType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StepOutcomeAuditor} 单元测试（Phase 14·收口：步骤产出审计提取）。
 *
 * <p>把 {@code PipelineOrchestrator} 内联的 per-step 审计 + markDegraded 逻辑提取为共享收口，
 * 供线性编排器与 LangGraph {@code GraphNode} 复用——避免两模式各写一份审计逻辑（④统一收口）。
 * 语义与原编排器逐字一致（detail 前缀"短路:"/"降级:"/"异常:" + stepName + scenario/message，
 * 类型经 {@link AuditEventType#from}）。
 */
class StepOutcomeAuditorTest {

    @Test
    void audit_proceed_emitsNoEvent() {
        PipelineContext context = new PipelineContext("trace", "sess", "hi");

        StepOutcomeAuditor.audit(context, "StepX", new StepOutcome.Proceed());

        assertThat(context.auditEvents()).isEmpty();
    }

    @Test
    void audit_shortCircuit_emitsScenarioMappedEvent() {
        PipelineContext context = new PipelineContext("trace", "sess", "hi");

        StepOutcomeAuditor.audit(context, "StepX",
                new StepOutcome.ShortCircuit(DegradationScenario.HITL_TIMEOUT));

        List<AuditEvent> events = context.auditEvents();
        assertThat(events).hasSize(1);
        AuditEvent e = events.get(0);
        assertThat(e.type()).isEqualTo(AuditEventType.HITL);
        assertThat(e.traceId()).isEqualTo("trace");
        assertThat(e.sessionId()).isEqualTo("sess");
        assertThat(e.detail()).isEqualTo("短路:StepX:HITL_TIMEOUT");
    }

    @Test
    void audit_degrade_emitsEventAndMarksDegraded() {
        PipelineContext context = new PipelineContext("trace", "sess", "hi");

        StepOutcomeAuditor.audit(context, "StepX",
                new StepOutcome.Degrade(DegradationScenario.SESSION_DOWN));

        List<AuditEvent> events = context.auditEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AuditEventType.SESSION_DOWN);
        assertThat(events.get(0).detail()).isEqualTo("降级:StepX:SESSION_DOWN");
        assertThat(context.degraded()).isTrue();
        assertThat(context.scenario()).isEqualTo(DegradationScenario.SESSION_DOWN);
    }

    @Test
    void auditException_emitsExceptionEvent() {
        PipelineContext context = new PipelineContext("trace", "sess", "hi");

        StepOutcomeAuditor.auditException(context, "StepX", "boom");

        List<AuditEvent> events = context.auditEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AuditEventType.EXCEPTION);
        assertThat(events.get(0).detail()).isEqualTo("异常:StepX:boom");
    }
}
