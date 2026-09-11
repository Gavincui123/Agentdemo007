package com.agentdemo007.langgraph;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * langgraph4j-core 1.5.14 冒烟测试（Phase 14·依赖可用性验证）。
 *
 * <p>验收标准 1「LangGraph4j 依赖加载成功，无版本冲突」的最小工程验证：确认库在
 * Boot 4.1 / Jackson 3 / JDK 17 环境下可构造状态图、编译、同步 invoke 并取回终态。
 * 这是第三方库的可用性特征测试（非项目生产代码测试），故一经编写即应通过——
 * 它是后续 {@code GraphNode}/{@code GraphExecutor} 的基石护栏，防止依赖升级静默断链。
 */
class LangGraphSmokeTest {

    @Test
    void stateGraph_compilesAndInvokes_linearGraph() throws Exception {
        // 最简线性图：START → greet → END；greet 节点把 input 前缀化后写入 output
        StateGraph<AgentState> graph = new StateGraph<>(
                Map.of(),
                AgentState::new);

        graph.addNode("greet", AsyncNodeAction.node_async(state ->
                Map.of("output", "HELLO:" + state.value("input").orElse(""))));
        graph.addEdge(StateGraph.START, "greet");
        graph.addEdge("greet", StateGraph.END);

        CompiledGraph<AgentState> compiled = graph.compile();

        Optional<AgentState> result = compiled.invoke(Map.of("input", "world"));

        assertThat(result).isPresent();
        assertThat(result.get().value("output").orElse(""))
                .isEqualTo("HELLO:world");
    }
}
