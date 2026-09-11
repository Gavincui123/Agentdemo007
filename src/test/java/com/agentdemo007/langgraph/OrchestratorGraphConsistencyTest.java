package com.agentdemo007.langgraph;

import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineExecutor;
import com.agentdemo007.common.pipeline.PipelineOrchestrator;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 线性编排器 vs 图编排器输出一致性测试（Phase 14·验收：编排模式与直接链路输出结果一致）。
 *
 * <p>同一份 {@link PipelineStep} 列表分别经 {@link PipelineOrchestrator}（线性）与
 * {@link GraphExecutor}（图）驱动，断言终态 {@link PipelineResult} 逐字段相等
 * （reply/degraded/scenario）。覆盖四条终态路径：全 Proceed / 短路 / 降级 / 异常。
 *
 * <p>同一份 step 实现两模式复用——④统一收口（换引擎不换收口）的机械保证：
 * GraphNode 复用 {@code step.process()}、StepOutcomeAuditor 复用同一份审计逻辑、
 * GraphExecutor.terminal 镜像 {@code PipelineOrchestrator.terminal}。本测试是这条不变量的
 * 回归守卫（characterization test：锁定既有不变量，防未来改动悄悄破坏两模式等价）。
 */
class OrchestratorGraphConsistencyTest {

    private final DegradationPhraseCenter phraseCenter = new DegradationPhraseCenter();

    /** 桩：定名 + 追加 tag 到 finalReply，Proceed。 */
    static class NamedAppend implements PipelineStep {
        private final String name;
        private final String tag;

        NamedAppend(String name, String tag) {
            this.name = name;
            this.tag = tag;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public StepOutcome process(PipelineContext ctx) {
            String prev = ctx.finalReply() == null ? "" : ctx.finalReply();
            ctx.setFinalReply(prev + tag);
            return new StepOutcome.Proceed();
        }
    }

    /** 桩：定名 + 返回 ShortCircuit(scenario)。 */
    static class NamedShortCircuit implements PipelineStep {
        private final String name;
        private final DegradationScenario scenario;

        NamedShortCircuit(String name, DegradationScenario scenario) {
            this.name = name;
            this.scenario = scenario;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public StepOutcome process(PipelineContext ctx) {
            return new StepOutcome.ShortCircuit(scenario);
        }
    }

    /** 桩：定名 + 返回 Degrade(scenario)。 */
    static class NamedDegrade implements PipelineStep {
        private final String name;
        private final DegradationScenario scenario;

        NamedDegrade(String name, DegradationScenario scenario) {
            this.name = name;
            this.scenario = scenario;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public StepOutcome process(PipelineContext ctx) {
            return new StepOutcome.Degrade(scenario);
        }
    }

    /** 桩：定名 + 抛 RuntimeException。 */
    static class NamedThrowing implements PipelineStep {
        private final String name;
        private final String msg;

        NamedThrowing(String name, String msg) {
            this.name = name;
            this.msg = msg;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public StepOutcome process(PipelineContext ctx) {
            throw new RuntimeException(msg);
        }
    }

    /** 同一份 steps 分别走线性/图编排，断言终态 PipelineResult 逐字段相等。 */
    private void assertConsistent(List<PipelineStep> steps) {
        // 经统一 PipelineExecutor 接口驱动（换引擎不换出口，④统一收口）——
        // 线性 / 图两实现都满足该接口，调用方（ChatController）只依赖接口
        PipelineExecutor linear = new PipelineOrchestrator(steps, phraseCenter);
        PipelineExecutor graph = new GraphExecutor(steps, phraseCenter);

        PipelineResult linearResult = linear.run(new PipelineContext("trace", "sess", "hi"));
        PipelineResult graphResult = graph.run(new PipelineContext("trace", "sess", "hi"));

        assertThat(graphResult)
                .as("图编排与线性编排终态须逐字段一致（reply/degraded/scenario）")
                .isEqualTo(linearResult);
    }

    @Test
    void consistent_allProceed() {
        assertConsistent(List.of(
                new NamedAppend("appendA", "A"),
                new NamedAppend("appendB", "B")));
    }

    @Test
    void consistent_shortCircuit() {
        assertConsistent(List.of(
                new NamedShortCircuit("sc", DegradationScenario.SESSION_DOWN),
                new NamedAppend("appendB", "B")));
    }

    @Test
    void consistent_degrade() {
        assertConsistent(List.of(
                new NamedDegrade("deg", DegradationScenario.RAG_SKIP),
                new NamedAppend("appendB", "B")));
    }

    @Test
    void consistent_exception() {
        assertConsistent(List.of(
                new NamedThrowing("throw", "boom"),
                new NamedAppend("appendB", "B")));
    }
}
