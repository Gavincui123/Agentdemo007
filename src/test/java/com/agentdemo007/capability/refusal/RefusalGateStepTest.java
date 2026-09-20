package com.agentdemo007.capability.refusal;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.intent.Intent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拒答闸门单测（[[refusal-design]]）。
 *
 * <p>覆盖裁决矩阵：strict×(groundingMiss×证据×意图×抢占收口) 与 prompt/off 模式的零侵入性。
 * 拒答语义收口断言：strict 命中 → presetReply 话术 + REFUSAL 审计 + <b>不标记 degraded</b>
 * （业务级正确行为≠系统降级）。
 */
class RefusalGateStepTest {

    private static RefusalProperties props(RefusalProperties.Mode mode) {
        RefusalProperties p = new RefusalProperties();
        p.setMode(mode);
        return p;
    }

    /** 基线：groundingMiss + OTHER 意图 + 全证据通道空。 */
    private static PipelineContext missContext() {
        PipelineContext ctx = new PipelineContext("s1", "退款政策是什么？");
        ctx.setIntent(Intent.OTHER);
        ctx.setGroundingMiss(true);
        return ctx;
    }

    @Test
    void strictMissNoEvidenceSetsPresetReplyAndAuditButNotDegraded() {
        PipelineContext ctx = missContext();
        StepOutcome outcome = new RefusalGateStep(props(RefusalProperties.Mode.STRICT)).process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.presetReply()).isNotBlank().contains("无法作答");
        assertThat(ctx.groundingMiss()).isTrue();
        assertThat(ctx.degraded()).isFalse(); // 拒答≠降级
        assertThat(ctx.auditEvents()).hasSize(1);
        assertThat(ctx.auditEvents().get(0).type()).isEqualTo(com.agentdemo007.observability.audit.AuditEventType.REFUSAL);
    }

    @Test
    void strictWithRagFragmentsDoesNotRefuse() {
        PipelineContext ctx = missContext();
        ctx.setRagFragments(java.util.List.of("退款政策：7日无理由。"));
        new RefusalGateStep(props(RefusalProperties.Mode.STRICT)).process(ctx);
        assertThat(ctx.presetReply()).isNull();
    }

    @Test
    void strictWithRuntimeFactsDoesNotRefuse() {
        PipelineContext ctx = missContext();
        ctx.setRuntimeFacts(java.util.List.of("订单 ORD-001：状态已发货"));
        new RefusalGateStep(props(RefusalProperties.Mode.STRICT)).process(ctx);
        assertThat(ctx.presetReply()).isNull();
    }

    @Test
    void strictWithoutGroundingMissDoesNotRefuse() {
        PipelineContext ctx = missContext();
        ctx.setGroundingMiss(false); // 闲聊/计划免 RAG 的正常跳过
        new RefusalGateStep(props(RefusalProperties.Mode.STRICT)).process(ctx);
        assertThat(ctx.presetReply()).isNull();
    }

    @Test
    void strictExemptsKnowledgeIndependentIntents() {
        for (Intent intent : new Intent[]{Intent.CHIT_CHAT, Intent.REASONING,
                Intent.LONG_CONTEXT, Intent.STRUCTURED_EXTRACTION,
                Intent.TRANSFER_TO_HUMAN, Intent.INJECTION}) {
            PipelineContext ctx = missContext();
            ctx.setIntent(intent);
            new RefusalGateStep(props(RefusalProperties.Mode.STRICT)).process(ctx);
            assertThat(ctx.presetReply()).as("intent=%s 不应拒答", intent).isNull();
        }
    }

    @Test
    void strictDoesNotStealPresetReplyOrConcurrentReply() {
        PipelineContext rejected = missContext();
        rejected.setPresetReply("订单不存在，请核对后重试。");
        new RefusalGateStep(props(RefusalProperties.Mode.STRICT)).process(rejected);
        assertThat(rejected.presetReply()).isEqualTo("订单不存在，请核对后重试。"); // 业务驳回优先

        PipelineContext merged = missContext();
        merged.setConcurrentReply(new com.agentdemo007.common.pipeline.ConcurrentReply(null, null, null));
        new RefusalGateStep(props(RefusalProperties.Mode.STRICT)).process(merged);
        assertThat(merged.presetReply()).isNull();
    }

    @Test
    void promptModeOnlyProceedsWithoutSideEffects() {
        PipelineContext ctx = missContext();
        StepOutcome outcome = new RefusalGateStep(props(RefusalProperties.Mode.PROMPT)).process(ctx);
        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.presetReply()).isNull(); // 软约束交 SystemAnchorLayer 注入，本步不短路
        assertThat(ctx.auditEvents()).isEmpty();
    }

    @Test
    void offModeDisablesGateEntirely() {
        PipelineContext ctx = missContext();
        new RefusalGateStep(props(RefusalProperties.Mode.OFF)).process(ctx);
        assertThat(ctx.presetReply()).isNull();
    }
}
