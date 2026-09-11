package com.agentdemo007.langgraph;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GraphNode} 单元测试（Phase 14·图节点适配层）。
 *
 * <p>GraphNode 把既有 {@link PipelineStep}（意图识别/能力执行/上下文构建/网关调用/输出校验）
 * 适配为 langgraph4j 节点：入参从 langgraph4j 共享状态取 {@link PipelineContext}，
 * 调 {@code step.process(context)}，经共享 {@code StepOutcomeAuditor} 应用 per-step 副作用
 * （审计 + markDegraded，与线性编排器等价），再把产出的 {@link StepOutcome}（收口 sealed 类型）
 * 作为路由信号发射到状态更新图。步骤抛异常 → 审计 + 发 {@code ShortCircuit(INTERNAL)} 路由至 END。
 */
class GraphNodeTest {

    /** 桩步骤：置 finalReply="processed" 并返回 Proceed。 */
    static class ProceedStep implements PipelineStep {
        @Override
        public StepOutcome process(PipelineContext context) {
            context.setFinalReply("processed");
            return new StepOutcome.Proceed();
        }
    }

    /** 桩步骤：定名 + 固定产出（审计 detail 断言需要确定的 step.name()）。 */
    static class NamedStep implements PipelineStep {
        private final String name;
        private final StepOutcome outcome;

        NamedStep(String name, StepOutcome outcome) {
            this.name = name;
            this.outcome = outcome;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public StepOutcome process(PipelineContext context) {
            return outcome;
        }
    }

    /** 桩步骤：定名 + 抛异常（验证异常收口到 INTERNAL 短路）。 */
    static class ThrowingStep implements PipelineStep {
        private final String name;
        private final String message;

        ThrowingStep(String name, String message) {
            this.name = name;
            this.message = message;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public StepOutcome process(PipelineContext context) {
            throw new RuntimeException(message);
        }
    }

    private AgentState stateWith(PipelineContext context) {
        return new AgentState(new HashMap<>(Map.of(GraphNode.CONTEXT_KEY, context)));
    }

    @Test
    void name_delegatesToWrappedStep() {
        GraphNode node = new GraphNode(new ProceedStep());

        assertThat(node.name()).isEqualTo("ProceedStep");
    }

    @Test
    void asAsyncNodeAction_runsStepAndEmitsProceedOutcome() throws Exception {
        GraphNode node = new GraphNode(new ProceedStep());
        PipelineContext context = new PipelineContext("trace", "sess", "hello");
        AgentState state = stateWith(context);

        Map<String, Object> update = node.asAsyncNodeAction().apply(state).join();

        // 步骤被执行：PipelineContext 被副作用更新（强类型字段，非 Map 散落）
        assertThat(context.finalReply()).isEqualTo("processed");
        // Proceed 产出作为路由信号发射到状态更新图（GraphEdge 据 OUTCOME_KEY 路由）
        assertThat(update.get(GraphNode.OUTCOME_KEY))
                .isInstanceOf(StepOutcome.Proceed.class);
    }

    @Test
    void asAsyncNodeAction_emitsShortCircuitOutcome() throws Exception {
        PipelineStep step = ctx -> new StepOutcome.ShortCircuit(DegradationScenario.INTERNAL);
        GraphNode node = new GraphNode(step);
        AgentState state = stateWith(new PipelineContext("trace", "sess", "hi"));

        Map<String, Object> update = node.asAsyncNodeAction().apply(state).join();

        // 短路产出原样发射——降级话术/审计由 GraphExecutor 收口解释，本类不越权
        Object outcome = update.get(GraphNode.OUTCOME_KEY);
        assertThat(outcome).isInstanceOf(StepOutcome.ShortCircuit.class);
        assertThat(((StepOutcome.ShortCircuit) outcome).scenario())
                .isEqualTo(DegradationScenario.INTERNAL);
    }

    @Test
    void asAsyncNodeAction_emitsDegradeOutcome() throws Exception {
        PipelineStep step = ctx -> new StepOutcome.Degrade(DegradationScenario.RAG_SKIP);
        GraphNode node = new GraphNode(step);
        AgentState state = stateWith(new PipelineContext("trace", "sess", "hi"));

        Map<String, Object> update = node.asAsyncNodeAction().apply(state).join();

        Object outcome = update.get(GraphNode.OUTCOME_KEY);
        assertThat(outcome).isInstanceOf(StepOutcome.Degrade.class);
        assertThat(((StepOutcome.Degrade) outcome).scenario())
                .isEqualTo(DegradationScenario.RAG_SKIP);
    }

    @Test
    void asAsyncNodeAction_appliesDegradeSideEffects() throws Exception {
        GraphNode node = new GraphNode(
                new NamedStep("RagStep", new StepOutcome.Degrade(DegradationScenario.RAG_SKIP)));
        PipelineContext context = new PipelineContext("trace", "sess", "hi");
        AgentState state = stateWith(context);

        Map<String, Object> update = node.asAsyncNodeAction().apply(state).join();

        // 降级副作用（镜像线性编排器 per-step）：标记 degraded + 审计事件——
        // GraphNode 须调 StepOutcomeAuditor.audit，否则两模式产出不等价（④统一收口）
        assertThat(context.degraded()).isTrue();
        assertThat(context.scenario()).isEqualTo(DegradationScenario.RAG_SKIP);
        assertThat(context.auditEvents()).hasSize(1);
        assertThat(context.auditEvents().get(0).detail()).isEqualTo("降级:RagStep:RAG_SKIP");
        // outcome 仍原样发射供 GraphEdge 路由（Degrade→下一节点）
        assertThat(update.get(GraphNode.OUTCOME_KEY)).isInstanceOf(StepOutcome.Degrade.class);
    }

    @Test
    void asAsyncNodeAction_appliesShortCircuitAudit() throws Exception {
        GraphNode node = new GraphNode(
                new NamedStep("InjectionStep",
                        new StepOutcome.ShortCircuit(DegradationScenario.INJECTION)));
        PipelineContext context = new PipelineContext("trace", "sess", "hi");
        AgentState state = stateWith(context);

        Map<String, Object> update = node.asAsyncNodeAction().apply(state).join();

        // 短路审计（不标 degraded，仅审计，话术由 GraphExecutor 终端收口）
        assertThat(context.degraded()).isFalse();
        assertThat(context.auditEvents()).hasSize(1);
        assertThat(context.auditEvents().get(0).detail()).isEqualTo("短路:InjectionStep:INJECTION");
        assertThat(update.get(GraphNode.OUTCOME_KEY)).isInstanceOf(StepOutcome.ShortCircuit.class);
    }

    @Test
    void asAsyncNodeAction_catchesExceptionAndEmitsInternalShortCircuit() throws Exception {
        GraphNode node = new GraphNode(new ThrowingStep("BadStep", "boom"));
        PipelineContext context = new PipelineContext("trace", "sess", "hi");
        AgentState state = stateWith(context);

        Map<String, Object> update = node.asAsyncNodeAction().apply(state).join();

        // 异常收口（镜像线性编排器 per-step catch）：审计 "异常:BadStep:boom" +
        // 发射 ShortCircuit(INTERNAL) 供 GraphEdge 路由至 END（不再继续后续节点）
        assertThat(context.auditEvents()).hasSize(1);
        assertThat(context.auditEvents().get(0).detail()).isEqualTo("异常:BadStep:boom");
        Object outcome = update.get(GraphNode.OUTCOME_KEY);
        assertThat(outcome).isInstanceOf(StepOutcome.ShortCircuit.class);
        assertThat(((StepOutcome.ShortCircuit) outcome).scenario())
                .isEqualTo(DegradationScenario.INTERNAL);
    }
}
