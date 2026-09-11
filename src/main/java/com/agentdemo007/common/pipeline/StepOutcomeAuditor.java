package com.agentdemo007.common.pipeline;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.observability.audit.AuditEvent;
import com.agentdemo007.observability.audit.AuditEventType;

/**
 * 步骤产出审计收口（Phase 14·per-step 审计 + 降级标记的单一真相源）。
 *
 * <p>把 {@link PipelineOrchestrator} 内联的 per-step 审计 + {@code markDegraded} 逻辑提取为共享收口，
 * 供线性编排器与 LangGraph {@code GraphNode} 复用——避免两模式各写一份审计逻辑（④统一收口），
 * 使图编排与线性编排产出等价审计（验收：编排模式与直接链路输出结果一致）。
 *
 * <p>语义：Proceed/Retry 无审计（推进/重试，非降级）；ShortCircuit 审计 + 由调用方收口话术；
 * Degrade 审计 + {@code markDegraded}；异常单独走 {@link #auditException}（调用方收口 INTERNAL 话术）。
 * detail 前缀"短路:"/"降级:"/"异常:" + stepName + scenario/message，
 * 类型经 {@link AuditEventType#from(DegradationScenario)} 映射（Degrade 用场景特定类型，非通用 DEGRADATION）。
 */
public final class StepOutcomeAuditor {

    private StepOutcomeAuditor() {
    }

    /** 审计正常产出：Proceed/Retry 无审计；ShortCircuit/Degrade 审计；Degrade 额外 markDegraded。 */
    public static void audit(PipelineContext context, String stepName, StepOutcome outcome) {
        if (outcome instanceof StepOutcome.ShortCircuit sc) {
            context.addAuditEvent(AuditEvent.of(AuditEventType.from(sc.scenario()),
                    context.traceId(), context.sessionId(),
                    "短路:" + stepName + ":" + sc.scenario().name()));
        } else if (outcome instanceof StepOutcome.Degrade d) {
            context.addAuditEvent(AuditEvent.of(AuditEventType.from(d.scenario()),
                    context.traceId(), context.sessionId(),
                    "降级:" + stepName + ":" + d.scenario().name()));
            context.markDegraded(d.scenario());
        }
        // Proceed/Retry：无审计（推进/重试，非降级）
    }

    /** 审计步骤异常（调用方收口到 INTERNAL 话术，本方法不产话术）。 */
    public static void auditException(PipelineContext context, String stepName, String message) {
        context.addAuditEvent(AuditEvent.of(AuditEventType.EXCEPTION,
                context.traceId(), context.sessionId(),
                "异常:" + stepName + ":" + message));
    }
}
