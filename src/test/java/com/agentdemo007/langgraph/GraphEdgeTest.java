package com.agentdemo007.langgraph;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.StepOutcome;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GraphEdge} 单元测试（Phase 14·条件分支边）。
 *
 * <p>GraphEdge 把上一步 {@link GraphNode} 发射到状态里的 {@link StepOutcome} 映射为
 * langgraph4j 条件边的分支键——langgraph4j {@code addConditionalEdges} 据 EdgeAction
 * 返回值在路由表 {@code Map<String,String>} 中查目标节点。Proceed/Degrade 继续推进
 * （Degrade 仅标记不阻塞），ShortCircuit 跳到 END（零 LLM 短路）。
 */
class GraphEdgeTest {

    private AgentState stateWithOutcome(StepOutcome outcome) {
        return new AgentState(new HashMap<>(Map.of(GraphNode.OUTCOME_KEY, outcome)));
    }

    @Test
    void asAsyncEdgeAction_proceedOutcome_routesToProceedBranch() throws Exception {
        GraphEdge edge = new GraphEdge();

        String branch = edge.asAsyncEdgeAction()
                .apply(stateWithOutcome(new StepOutcome.Proceed())).join();

        assertThat(branch).isEqualTo(GraphEdge.PROCEED_BRANCH);
    }

    @Test
    void asAsyncEdgeAction_shortCircuitOutcome_routesToShortCircuitBranch() throws Exception {
        GraphEdge edge = new GraphEdge();

        String branch = edge.asAsyncEdgeAction()
                .apply(stateWithOutcome(new StepOutcome.ShortCircuit(DegradationScenario.INTERNAL)))
                .join();

        assertThat(branch).isEqualTo(GraphEdge.SHORT_CIRCUIT_BRANCH);
    }

    @Test
    void asAsyncEdgeAction_degradeOutcome_routesToDegradeBranch() throws Exception {
        GraphEdge edge = new GraphEdge();

        String branch = edge.asAsyncEdgeAction()
                .apply(stateWithOutcome(new StepOutcome.Degrade(DegradationScenario.RAG_SKIP)))
                .join();

        assertThat(branch).isEqualTo(GraphEdge.DEGRADE_BRANCH);
    }
}
