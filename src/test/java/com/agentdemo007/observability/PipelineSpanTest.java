package com.agentdemo007.observability;

import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineOrchestrator;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编排器 span 层级单测（Phase 23）：root（agent.pipeline）+ per-step（agent.step）父子关系、
 * outcome/scenario 属性、耗时 Timer（补 per-step 无计时缺口）。
 *
 * <p>用 InMemorySpanExporter 收真实 SDK 的 span（opentelemetry-sdk-testing）——
 * {@link AgentTracer} 门面注入真 Tracer，验证业务收口与 OTel 的结合产物。
 */
class PipelineSpanTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    private final AgentTracer tracer = new AgentTracer(
            OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("test"));

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    void run_createsRootAndStepSpansWithHierarchyAndAttrs() {
        PipelineStep proceed = step("step_a", new StepOutcome.Proceed());
        PipelineStep degrade = step("step_b", new StepOutcome.Degrade(DegradationScenario.RAG_SKIP));
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(proceed, degrade, replyStep("已按降级口径回答")),
                new DegradationPhraseCenter(), AgentMetrics.NO_OP, tracer);

        PipelineResult result = orchestrator.run(new PipelineContext("sess-span", "你好"));

        assertThat(result.degraded()).isTrue();
        List<SpanData> spans = exporter.getFinishedSpanItems();

        // root span：agent.pipeline，mode 属性 = linear，恰一个
        List<SpanData> roots = spans.stream().filter(s -> "agent.pipeline".equals(s.getName())).toList();
        assertThat(roots).hasSize(1);
        SpanData root = roots.get(0);
        assertThat(root.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(root.getAttributes().get(AttributeKey.stringKey("agent.pipeline.mode"))).isEqualTo("linear");
        assertThat(root.getAttributes().get(AttributeKey.stringKey("agent.session.id"))).isEqualTo("sess-span");

        // per-step span：每步一个，父 = root，outcome/scenario 属性正确
        assertThat(spans.stream().filter(s -> "agent.step".equals(s.getName())).count()).isEqualTo(3);
        SpanData degradeSpan = spans.stream()
                .filter(s -> "step_b".equals(s.getAttributes().get(AttributeKey.stringKey("agent.step.name"))))
                .findFirst().orElseThrow();
        assertThat(degradeSpan.getAttributes().get(AttributeKey.stringKey("agent.step.outcome"))).isEqualTo("degrade");
        assertThat(degradeSpan.getAttributes().get(AttributeKey.stringKey("agent.step.scenario")))
                .isEqualTo(DegradationScenario.RAG_SKIP.name());
        assertThat(degradeSpan.getParentSpanId()).isEqualTo(root.getSpanId());

        SpanData proceedSpan = spans.stream()
                .filter(s -> "step_a".equals(s.getAttributes().get(AttributeKey.stringKey("agent.step.name"))))
                .findFirst().orElseThrow();
        assertThat(proceedSpan.getAttributes().get(AttributeKey.stringKey("agent.step.outcome"))).isEqualTo("proceed");
        assertThat(proceedSpan.getAttributes().get(AttributeKey.stringKey("agent.step.scenario"))).isEqualTo("none");
    }

    @Test
    void run_stepException_recordsErrorOnStepSpan() {
        PipelineStep boom = new PipelineStep() {
            @Override
            public String name() {
                return "step_boom";
            }

            @Override
            public StepOutcome process(PipelineContext context) {
                throw new IllegalStateException("boom");
            }
        };
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(boom), new DegradationPhraseCenter(), AgentMetrics.NO_OP, tracer);

        PipelineResult result = orchestrator.run(new PipelineContext("sess-boom", "你好"));

        // 异常收口：INTERNAL 话术短路（短路=degraded=true + scenario=INTERNAL，§5.12 不抛 5xx）；
        // span 记录 error + exception outcome
        assertThat(result.degraded()).isTrue();
        assertThat(result.scenario()).isEqualTo(DegradationScenario.INTERNAL.name());
        SpanData boomSpan = exporter.getFinishedSpanItems().stream()
                .filter(s -> "step_boom".equals(s.getAttributes().get(AttributeKey.stringKey("agent.step.name"))))
                .findFirst().orElseThrow();
        assertThat(boomSpan.getAttributes().get(AttributeKey.stringKey("agent.step.outcome"))).isEqualTo("exception");
        assertThat(boomSpan.getAttributes().get(AttributeKey.stringKey("agent.step.scenario")))
                .isEqualTo(DegradationScenario.INTERNAL.name());
        assertThat(boomSpan.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
        assertThat(boomSpan.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void run_withNoOpTracer_zeroSideEffects() {
        // NO_OP 门面（单测/未装配口径）：行为与无 OTel 完全一致
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                List.of(step("step_a", new StepOutcome.Proceed()), replyStep("您好")),
                new DegradationPhraseCenter(), AgentMetrics.NO_OP, AgentTracer.NO_OP);

        PipelineResult result = orchestrator.run(new PipelineContext("sess-noop", "你好"));

        assertThat(result.reply()).isEqualTo("您好");
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    private static PipelineStep step(String name, StepOutcome outcome) {
        return new PipelineStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public StepOutcome process(PipelineContext context) {
                return outcome;
            }
        };
    }

    private static PipelineStep replyStep(String reply) {
        return new PipelineStep() {
            @Override
            public String name() {
                return "reply";
            }

            @Override
            public StepOutcome process(PipelineContext context) {
                context.setFinalReply(reply);
                return new StepOutcome.Proceed();
            }
        };
    }
}
