package com.agentdemo007.langgraph;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentStateGraph} 单元测试（Phase 14·状态机定义）。
 *
 * <p>AgentStateGraph 把有序 {@link GraphNode} 列表（镜像流水线 @Order 顺序）编译为
 * langgraph4j {@code StateGraph}：START→首节点，节点间条件边（Proceed/Degrade→下一节点，
 * ShortCircuit→END 跳过后续），末节点→END。同一份 step 实现复用，与线性编排等价
 * （验收：编排模式与直接链路输出结果一致）。
 */
class AgentStateGraphTest {

    /** 桩步骤：name 自定（避开类名默认，便于同类多节点），把 tag 追加到 finalReply。 */
    static class NamedAppendStep implements PipelineStep {
        private final String name;
        private final String tag;

        NamedAppendStep(String name, String tag) {
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

    /** 桩步骤：直接短路（不设 finalReply，验证后续节点是否被跳过）。 */
    static class ShortCircuitStep implements PipelineStep {
        @Override
        public StepOutcome process(PipelineContext context) {
            return new StepOutcome.ShortCircuit(DegradationScenario.INTERNAL);
        }
    }

    private Map<String, Object> inputWith(PipelineContext context) {
        return new HashMap<>(Map.of(GraphNode.CONTEXT_KEY, context));
    }

    @Test
    void build_runsAllNodesInOrder_whenAllProceed() throws Exception {
        AgentStateGraph graph = new AgentStateGraph(List.of(
                new GraphNode(new NamedAppendStep("appendA", "A")),
                new GraphNode(new NamedAppendStep("appendB", "B"))));
        PipelineContext context = new PipelineContext("trace", "sess", "hi");

        graph.build().compile().invoke(inputWith(context));

        // A 先 B 后：A 置 "A"，B 追加 "B" → "AB"（也验证可变 context 跨节点传递）
        assertThat(context.finalReply()).isEqualTo("AB");
    }

    @Test
    void build_skipsSubsequentNodes_whenShortCircuit() throws Exception {
        AgentStateGraph graph = new AgentStateGraph(List.of(
                new GraphNode(new ShortCircuitStep()),
                new GraphNode(new NamedAppendStep("appendB", "B"))));
        PipelineContext context = new PipelineContext("trace", "sess", "hi");

        graph.build().compile().invoke(inputWith(context));

        // node1 短路 → 条件边 shortCircuit→END，node2 被跳过
        assertThat(context.finalReply()).isNull();
    }
}
