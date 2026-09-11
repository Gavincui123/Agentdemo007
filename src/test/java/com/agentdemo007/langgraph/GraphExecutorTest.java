package com.agentdemo007.langgraph;

import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.observability.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GraphExecutor} 单元测试（Phase 14·图执行终端收口）。
 *
 * <p>GraphExecutor 把同一份 {@link PipelineStep}（经 {@link GraphNode} 适配）编译为 langgraph4j
 * 图并驱动，终端收口为 {@link PipelineResult}——镜像线性 {@code PipelineOrchestrator} 的
 * {@code run()}/{@code terminal()} 语义：短路→话术；降级→标记后继续、终态 degraded；
 * 异常→INTERNAL 话术。四条终态路径覆盖：ok / shortCircuit / degraded / exception→INTERNAL
 * （验收：编排模式与直接链路输出结果一致）。
 */
class GraphExecutorTest {

    private final DegradationPhraseCenter phraseCenter = new DegradationPhraseCenter();

    /** 桩：定名 + 把 tag 追加到 finalReply 并 Proceed（验证顺序 + 可变 context 跨节点传递）。
     *  定名以避开同类多实例的 node-id 冲突（真实 step 是不同 @Component 类，类名天然不撞）。 */
    static class AppendStep implements PipelineStep {
        private final String name;
        private final String tag;

        AppendStep(String name, String tag) {
            this.name = name;
            this.tag = tag;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public StepOutcome process(PipelineContext context) {
            String prev = context.finalReply() == null ? "" : context.finalReply();
            context.setFinalReply(prev + tag);
            return new StepOutcome.Proceed();
        }
    }

    /** 桩：返回 Degrade(scenario)，不设 finalReply（验证降级不阻塞、终态 degraded）。 */
    static class DegradeStep implements PipelineStep {
        private final DegradationScenario scenario;

        DegradeStep(DegradationScenario scenario) {
            this.scenario = scenario;
        }

        @Override
        public StepOutcome process(PipelineContext context) {
            return new StepOutcome.Degrade(scenario);
        }
    }

    /** 桩：返回 ShortCircuit(scenario)（验证短路跳过后续、终态话术）。 */
    static class ShortCircuitStep implements PipelineStep {
        private final DegradationScenario scenario;

        ShortCircuitStep(DegradationScenario scenario) {
            this.scenario = scenario;
        }

        @Override
        public StepOutcome process(PipelineContext context) {
            return new StepOutcome.ShortCircuit(scenario);
        }
    }

    /** 桩：抛 RuntimeException（验证异常收口到 INTERNAL 短路）。 */
    static class ThrowingStep implements PipelineStep {
        private final String msg;

        ThrowingStep(String msg) {
            this.msg = msg;
        }

        @Override
        public StepOutcome process(PipelineContext context) {
            throw new RuntimeException(msg);
        }
    }

    /** 桩：前 {@code succeedAt} 次返回 Retry（自循环重试），达到后置 finalReply 并 Proceed
     * （验证图循环至成功——验收：工具自纠正场景下图可循环至成功）。 */
    static class RetryStep implements PipelineStep {
        private final AtomicInteger attempts = new AtomicInteger();
        private final int succeedAt;

        RetryStep(int succeedAt) {
            this.succeedAt = succeedAt;
        }

        int attempts() {
            return attempts.get();
        }

        @Override
        public StepOutcome process(PipelineContext context) {
            int n = attempts.incrementAndGet();
            if (n >= succeedAt) {
                context.setFinalReply("done");
                return new StepOutcome.Proceed();
            }
            return new StepOutcome.Retry();
        }
    }

    /** 桩：永远 Retry（验证达最大迭代收口——验收：达最大迭代）。 */
    static class AlwaysRetryStep implements PipelineStep {
        private final AtomicInteger attempts = new AtomicInteger();

        int attempts() {
            return attempts.get();
        }

        @Override
        public StepOutcome process(PipelineContext context) {
            attempts.incrementAndGet();
            return new StepOutcome.Retry();
        }
    }

    @Test
    void run_allProceed_returnsOkWithAccumulatedReply() {
        GraphExecutor executor = new GraphExecutor(
                List.of(new AppendStep("appendA", "A"), new AppendStep("appendB", "B")), phraseCenter);
        PipelineContext context = new PipelineContext("trace", "sess", "hi");

        PipelineResult result = executor.run(context);

        // 两节点顺序执行、finalReply 累积 "AB"；无降级 → ok
        assertThat(result.reply()).isEqualTo("AB");
        assertThat(result.degraded()).isFalse();
        assertThat(result.scenario()).isNull();
    }

    @Test
    void run_shortCircuitMidGraph_returnsPhraseAndSkipsRest() {
        GraphExecutor executor = new GraphExecutor(
                List.of(new ShortCircuitStep(DegradationScenario.SESSION_DOWN),
                        new AppendStep("appendB", "B")), phraseCenter);
        PipelineContext context = new PipelineContext("trace", "sess", "hi");

        PipelineResult result = executor.run(context);

        // node1 短路 → 条件边 shortCircuit→END，node2 被跳过；终态=话术（镜像线性编排器）
        assertThat(result.reply()).isEqualTo(phraseCenter.phrase(DegradationScenario.SESSION_DOWN));
        assertThat(result.degraded()).isTrue();
        assertThat(result.scenario()).isEqualTo("SESSION_DOWN");
        assertThat(context.finalReply()).isNull();
    }

    @Test
    void run_degradeThenProceed_returnsDegradedWithReply() {
        GraphExecutor executor = new GraphExecutor(
                List.of(new DegradeStep(DegradationScenario.RAG_SKIP),
                        new AppendStep("appendB", "B")), phraseCenter);
        PipelineContext context = new PipelineContext("trace", "sess", "hi");

        PipelineResult result = executor.run(context);

        // 降级不阻塞：node1 标记 RAG_SKIP 降级（markDegraded），node2 继续执行置 finalReply="B"；
        // 终态 degraded、reply 取 finalReply（非空不覆写为话术）——与线性编排器 terminal 一致
        assertThat(result.reply()).isEqualTo("B");
        assertThat(result.degraded()).isTrue();
        assertThat(result.scenario()).isEqualTo("RAG_SKIP");
    }

    @Test
    void run_stepException_returnsInternalPhrase() {
        GraphExecutor executor = new GraphExecutor(
                List.of(new ThrowingStep("boom"), new AppendStep("appendB", "B")), phraseCenter);
        PipelineContext context = new PipelineContext("trace", "sess", "hi");

        PipelineResult result = executor.run(context);

        // 异常收口（镜像线性编排器 per-step catch）：GraphNode 捕获→auditException+
        // 发 ShortCircuit(INTERNAL)→终端话术；node2 被跳过
        assertThat(result.reply()).isEqualTo(phraseCenter.phrase(DegradationScenario.INTERNAL));
        assertThat(result.degraded()).isTrue();
        assertThat(result.scenario()).isEqualTo("INTERNAL");
        assertThat(context.finalReply()).isNull();
        // 审计事件由 GraphNode 的 StepOutcomeAuditor.auditException 写入（④统一收口）
        assertThat(context.auditEvents()).hasSize(1);
        assertThat(context.auditEvents().get(0).detail()).isEqualTo("异常:ThrowingStep:boom");
    }

    @Test
    void run_retryLoop_loopsUntilSuccessWithinMaxIterations() {
        RetryStep step = new RetryStep(3); // 第 3 次成功
        GraphExecutor executor = new GraphExecutor(List.of(step), phraseCenter, 10);
        PipelineContext context = new PipelineContext("trace", "sess", "hi");

        PipelineResult result = executor.run(context);

        // 自循环 2 次 Retry，第 3 次 Proceed → 正常完成（验收：图可循环至成功）
        assertThat(step.attempts()).isEqualTo(3);
        assertThat(result.reply()).isEqualTo("done");
        assertThat(result.degraded()).isFalse();
        assertThat(result.scenario()).isNull();
    }

    @Test
    void run_retryLoop_hitsMaxIterations_returnsInternalPhrase() {
        AlwaysRetryStep step = new AlwaysRetryStep();
        GraphExecutor executor = new GraphExecutor(List.of(step), phraseCenter, 3);
        PipelineContext context = new PipelineContext("trace", "sess", "hi");

        PipelineResult result = executor.run(context);

        // 永远 Retry → 达最大迭代（平台护栏）→ 收口 INTERNAL 话术（验收：达最大迭代；
        // §5.12 全局兜底，§5.13 死循环护栏——异常计数监控由 maxIter 兜底）
        assertThat(result.reply()).isEqualTo(phraseCenter.phrase(DegradationScenario.INTERNAL));
        assertThat(result.degraded()).isTrue();
        assertThat(result.scenario()).isEqualTo("INTERNAL");
        // maxIter 护栏：节点未无限执行
        assertThat(step.attempts()).isLessThanOrEqualTo(3);
    }

    // ---- Phase 15 指标埋点（图编排终端收口镜像线性：duration + outcome）----

    @Test
    void run_allProceed_recordsDurationAndOkOutcome() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        GraphExecutor executor = new GraphExecutor(
                List.of(new AppendStep("a", "A"), new AppendStep("b", "B")), phraseCenter, 25, metrics);

        executor.run(new PipelineContext("t", "s", "hi"));

        assertThat(registry.timer("agent.pipeline.duration").count()).isEqualTo(1L);
        assertThat(registry.counter("agent.pipeline.outcome", "outcome", "ok", "scenario", "none").count())
                .isEqualTo(1.0);
    }

    @Test
    void run_shortCircuit_recordsShortCircuitOutcome() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        GraphExecutor executor = new GraphExecutor(
                List.of(new ShortCircuitStep(DegradationScenario.INJECTION)), phraseCenter, 25, metrics);

        executor.run(new PipelineContext("t", "s", "x"));

        assertThat(registry.counter("agent.pipeline.outcome",
                "outcome", "short_circuit", "scenario", "INJECTION").count()).isEqualTo(1.0);
    }

    @Test
    void run_degrade_recordsDegradedOutcome() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        GraphExecutor executor = new GraphExecutor(
                List.of(new DegradeStep(DegradationScenario.RAG_SKIP), new AppendStep("b", "B")),
                phraseCenter, 25, metrics);

        executor.run(new PipelineContext("t", "s", "x"));

        assertThat(registry.counter("agent.pipeline.outcome",
                "outcome", "degraded", "scenario", "RAG_SKIP").count()).isEqualTo(1.0);
    }

    @Test
    void run_stepThrows_recordsInternalShortCircuitOutcome() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        GraphExecutor executor = new GraphExecutor(
                List.of(new ThrowingStep("boom")), phraseCenter, 25, metrics);

        executor.run(new PipelineContext("t", "s", "x"));

        assertThat(registry.counter("agent.pipeline.outcome",
                "outcome", "short_circuit", "scenario", "INTERNAL").count()).isEqualTo(1.0);
    }
}
