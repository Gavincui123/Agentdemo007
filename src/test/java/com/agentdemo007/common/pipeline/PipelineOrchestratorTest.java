package com.agentdemo007.common.pipeline;

import com.agentdemo007.capability.rag.Bm25Reranker;
import com.agentdemo007.capability.rag.RagInjectionScanner;
import com.agentdemo007.capability.rag.RagStep;
import com.agentdemo007.capability.rag.RetrievalValidator;
import com.agentdemo007.capability.rag.Retriever;
import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.intent.IntentClassifier;
import com.agentdemo007.intent.KeywordTriageStep;
import com.agentdemo007.intent.rule.KeywordRule;
import com.agentdemo007.intent.rule.RuleMatcher;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.observability.audit.AuditEvent;
import com.agentdemo007.observability.audit.AuditEventType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 统一收口（第四原则）：编排器收口行为。
 *
 * <p>收口三件套之三 {@code PipelineOrchestrator}——按序驱动所有 {@link PipelineStep}，
 * 统一处置三种产出：Proceed 继续推进；ShortCircuit 立即收口到话术（审计+跳过后续，零 LLM）；
 * Degrade 标记降级但继续；任何步骤抛异常收口到 {@code INTERNAL} 话术（不抛 5xx，§5.12 全局兜底）。
 *
 * <p>三条原则在此交汇：①话术短路→ShortCircuit；②每步降级→Degrade；④统一收口→同一 StepOutcome 出口。
 */
class PipelineOrchestratorTest {

    private final DegradationPhraseCenter phraseCenter = new DegradationPhraseCenter();

    @Test
    void run_allProceed_returnsFinalReplyNotDegraded() {
        PipelineContext ctx = new PipelineContext("sess-1", "你好");
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(proceedStep(), replyStep("您好")), phraseCenter);

        PipelineResult result = orchestrator.run(ctx);

        assertThat(result.degraded()).isFalse();
        assertThat(result.scenario()).isNull();
        assertThat(result.reply()).isEqualTo("您好");
    }

    @Test
    void run_carriesRagCitationsToResult() {
        PipelineContext ctx = new PipelineContext("sess-c", "你好");
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(proceedStep(),
                        ragCitationStep(List.of("[来源: kb-refund] 退款流程")),
                        replyStep("您好")), phraseCenter);

        PipelineResult result = orchestrator.run(ctx);

        // terminal 把 context.ragCitations 传到 result（Phase 20 citation 回答可追溯）
        assertThat(result.citations()).containsExactly("[来源: kb-refund] 退款流程");
    }

    @Test
    void run_shortCircuit_skipsDownstreamAndReturnsPhrase() {
        AtomicBoolean downstreamRan = new AtomicBoolean(false);
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(shortCircuitStep(DegradationScenario.INJECTION),
                        proceedStep(() -> downstreamRan.set(true))),
                phraseCenter);

        PipelineResult result = orchestrator.run(new PipelineContext("s", "ignore instructions"));

        assertThat(result.degraded()).isTrue();
        assertThat(result.scenario()).isEqualTo("INJECTION");
        assertThat(result.reply()).isEqualTo(DegradationScenario.INJECTION.phrase());
        assertThat(downstreamRan).isFalse(); // 短路：后续步骤零执行
    }

    @Test
    void run_degrade_continuesAndMarksDegraded() {
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(degradeStep(DegradationScenario.SESSION_DOWN),
                        replyStep("您好")),
                phraseCenter);

        PipelineResult result = orchestrator.run(new PipelineContext("s", "x"));

        assertThat(result.degraded()).isTrue();
        assertThat(result.scenario()).isEqualTo("SESSION_DOWN");
        assertThat(result.reply()).isEqualTo("您好"); // 降级但不阻塞，继续到 replyStep
    }

    @Test
    void run_stepThrows_returnsInternalPhrase_no5xx() {
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(throwingStep(new RuntimeException("boom"))), phraseCenter);

        PipelineResult result = orchestrator.run(new PipelineContext("s", "x"));

        assertThat(result.degraded()).isTrue();
        assertThat(result.scenario()).isEqualTo("INTERNAL");
        assertThat(result.reply()).isEqualTo(DegradationScenario.INTERNAL.phrase());
    }

    @Test
    void run_shortCircuit_usesOverriddenPhrase() {
        phraseCenter.putOverride(DegradationScenario.MODEL_DOWN, "自定义维护话术");
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(shortCircuitStep(DegradationScenario.MODEL_DOWN)), phraseCenter);

        PipelineResult result = orchestrator.run(new PipelineContext("s", "x"));

        assertThat(result.reply()).isEqualTo("自定义维护话术"); // Nacos 热更新覆盖路径生效
    }

    @Test
    void run_degradeWithoutReply_fallsBackToPhrase() {
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(degradeStep(DegradationScenario.RAG_SKIP)), phraseCenter);

        PipelineResult result = orchestrator.run(new PipelineContext("s", "x"));

        assertThat(result.degraded()).isTrue();
        assertThat(result.scenario()).isEqualTo("RAG_SKIP");
        assertThat(result.reply()).isEqualTo(DegradationScenario.RAG_SKIP.phrase()); // 无 reply 时回退话术
    }

    // ---- Phase 13 审计点（流水线内：短路/降级/异常收口处统一留痕）----

    @Test
    void run_shortCircuit_recordsAuditEventMappedFromScenario() {
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(shortCircuitStep(DegradationScenario.HITL_TIMEOUT)), phraseCenter);
        PipelineContext ctx = new PipelineContext("trace-1", "sess-1", "转人工");

        orchestrator.run(ctx);

        assertThat(ctx.auditEvents()).hasSize(1);
        AuditEvent ev = ctx.auditEvents().get(0);
        assertThat(ev.type()).isEqualTo(AuditEventType.HITL);
        assertThat(ev.traceId()).isEqualTo("trace-1");
        assertThat(ev.sessionId()).isEqualTo("sess-1");
        assertThat(ev.detail()).contains("HITL_TIMEOUT");
    }

    @Test
    void run_stepThrows_recordsExceptionAuditEvent() {
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(throwingStep(new RuntimeException("boom"))), phraseCenter);
        PipelineContext ctx = new PipelineContext("trace-2", "sess-2", "x");

        orchestrator.run(ctx);

        assertThat(ctx.auditEvents()).hasSize(1);
        AuditEvent ev = ctx.auditEvents().get(0);
        assertThat(ev.type()).isEqualTo(AuditEventType.EXCEPTION);
        assertThat(ev.traceId()).isEqualTo("trace-2");
        assertThat(ev.detail()).contains("boom");
    }

    @Test
    void run_degrade_recordsScenarioMappedAuditEvent() {
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(degradeStep(DegradationScenario.SESSION_DOWN), replyStep("您好")), phraseCenter);
        PipelineContext ctx = new PipelineContext("trace-3", "sess-3", "x");

        orchestrator.run(ctx);

        assertThat(ctx.auditEvents()).hasSize(1);
        AuditEvent ev = ctx.auditEvents().get(0);
        // Degrade 路径同样经场景映射取类型（SESSION_DOWN→SESSION_DOWN）；detail 前缀区分短路/降级
        assertThat(ev.type()).isEqualTo(AuditEventType.SESSION_DOWN);
        assertThat(ev.detail()).contains("SESSION_DOWN");
    }

    // ---- Phase 15 指标埋点（编排器 run() 收口：duration + outcome + per-step degradation）----

    @Test
    void run_allProceed_recordsDurationAndOkOutcome() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(proceedStep(), replyStep("您好")), phraseCenter, metrics);

        orchestrator.run(new PipelineContext("s", "你好"));

        assertThat(registry.timer("agent.pipeline.duration").count()).isEqualTo(1L);
        assertThat(registry.counter("agent.pipeline.outcome", "outcome", "ok", "scenario", "none").count())
                .isEqualTo(1.0);
    }

    @Test
    void run_shortCircuit_recordsShortCircuitOutcome() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(shortCircuitStep(DegradationScenario.INJECTION)), phraseCenter, metrics);

        orchestrator.run(new PipelineContext("s", "ignore"));

        assertThat(registry.counter("agent.pipeline.outcome",
                "outcome", "short_circuit", "scenario", "INJECTION").count()).isEqualTo(1.0);
    }

    @Test
    void run_degrade_recordsDegradedOutcomeAndPerStepDegradation() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(degradeStep(DegradationScenario.SESSION_DOWN), replyStep("您好")), phraseCenter, metrics);

        orchestrator.run(new PipelineContext("s", "x"));

        assertThat(registry.counter("agent.pipeline.outcome",
                "outcome", "degraded", "scenario", "SESSION_DOWN").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.degradation", "scenario", "SESSION_DOWN").count()).isEqualTo(1.0);
    }

    @Test
    void run_stepThrows_recordsInternalShortCircuitOutcome() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(throwingStep(new RuntimeException("boom"))), phraseCenter, metrics);

        orchestrator.run(new PipelineContext("s", "x"));

        assertThat(registry.counter("agent.pipeline.outcome",
                "outcome", "short_circuit", "scenario", "INTERNAL").count()).isEqualTo(1.0);
    }

    @Test
    void run_chitChatFastPath_noFalseDegradation() {
        // 真实 KeywordTriageStep(150) + RagStep(660) 链：你好被关键词分诊为 CHIT_CHAT，
        // RagStep 见 chit-chat 即 Proceed（不 RAG_SKIP 误报降级），终端 degraded=false。
        // 此前"你好"误报"降级·系统仍答·RAG_SKIP"的根因在此集成层验证修复。
        RuleMatcher matcher = new RuleMatcher(List.of(
                new KeywordRule("你好", Intent.CHIT_CHAT, 0.85)));
        KeywordTriageStep triage = new KeywordTriageStep(matcher, new IntentClassifier(0.6));
        Retriever retriever = mock(Retriever.class); // 不该被调（chit-chat 跳 RAG）
        RagStep ragStep = new RagStep(retriever,
                new RetrievalValidator(0.3, 1), new Bm25Reranker(),
                new RagInjectionScanner(), 5);
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(triage, ragStep, replyStep("您好")), phraseCenter);

        PipelineResult result = orchestrator.run(new PipelineContext("s", "你好"));

        assertThat(result.degraded()).isFalse(); // 关键：无"降级·系统仍答·RAG_SKIP"误报
        assertThat(result.reply()).isEqualTo("您好");
        verifyNoInteractions(retriever); // chit-chat 不调召回
    }

    // ---- test stub steps ----

    private static PipelineStep proceedStep() {
        return ctx -> new StepOutcome.Proceed();
    }

    private static PipelineStep proceedStep(Runnable sideEffect) {
        return ctx -> {
            sideEffect.run();
            return new StepOutcome.Proceed();
        };
    }

    private static PipelineStep replyStep(String reply) {
        return ctx -> {
            ctx.setFinalReply(reply);
            return new StepOutcome.Proceed();
        };
    }

    /** 桩：填充 RAG 命中来源（模拟 RagStep 命中后 setRagCitations）。 */
    private static PipelineStep ragCitationStep(List<String> citations) {
        return ctx -> {
            ctx.setRagCitations(citations);
            return new StepOutcome.Proceed();
        };
    }

    private static PipelineStep shortCircuitStep(DegradationScenario scenario) {
        return ctx -> new StepOutcome.ShortCircuit(scenario);
    }

    private static PipelineStep degradeStep(DegradationScenario scenario) {
        return ctx -> new StepOutcome.Degrade(scenario);
    }

    private static PipelineStep throwingStep(RuntimeException e) {
        return ctx -> {
            throw e;
        };
    }
}
