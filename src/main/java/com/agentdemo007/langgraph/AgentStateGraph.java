package com.agentdemo007.langgraph;

import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;

/**
 * Agent 状态机定义（Phase 14·状态节点 + 共享状态）。
 *
 * <p>把有序 {@link GraphNode} 列表（镜像流水线 {@code @Order} 顺序）编译为 langgraph4j
 * {@link StateGraph}：START→首节点，节点间条件边（{@link GraphEdge}）：
 * <ul>
 *   <li>Proceed / Degrade → 下一节点（Degrade 仅标记不阻塞，§5.12）；</li>
 *   <li>ShortCircuit → END（跳过后续，零 LLM 短路，①话术短路）。</li>
 * </ul>
 * 末节点→END。
 *
 * <p>共享状态：langgraph4j {@link AgentState}（Map 载体）携带 {@link GraphNode#CONTEXT_KEY}
 * → {@code PipelineContext}（强类型收口状态）；业务字段全部经 {@code PipelineContext} 强类型访问器，
 * 不散落为 Map 键（§5.14）。同一份 step 实现在线性/图编排下复用——两模式输出一致。
 *
 * <p>本类只产出"定义"（{@link StateGraph}）；编译与驱动由 {@code GraphExecutor}（T61）负责，
 * 职责分离：结构 vs 执行。线性等价：本图按同一有序 step 列表串接，与 {@code PipelineOrchestrator}
 * 的线性驱动语义一致，仅多了显式条件边（为循环/条件跳转预留，T61 启用）。
 */
public class AgentStateGraph {

    private final List<GraphNode> nodes;
    private final GraphEdge edge;

    public AgentStateGraph(List<GraphNode> nodes) {
        this.nodes = nodes;
        this.edge = new GraphEdge();
    }

    /**
     * 构造 langgraph4j 状态图定义。
     *
     * @throws GraphStateException 节点/边冲突（langgraph4j 图校验）
     */
    public StateGraph<AgentState> build() throws GraphStateException {
        // NoCloneStateSerializer per-request 新建（stashed 字段非线程安全）；
        // 避开 Java 序列化 PipelineContext，保持共享引用语义（详见该类 javadoc）
        StateGraph<AgentState> graph = new StateGraph<>(Map.of(),
                new NoCloneStateSerializer(AgentState::new));

        for (GraphNode node : nodes) {
            graph.addNode(node.name(), node.asAsyncNodeAction());
        }
        graph.addEdge(StateGraph.START, nodes.get(0).name());

        // 每节点条件边：Proceed/Degrade→下一节点（末节点→END），ShortCircuit→END（跳过后续，
        // 零 LLM 短路），Retry→本节点（自循环重试，工具自纠正 §5.13）。
        // Retry 分支对不重试的节点是死分支（永不触发），由 GraphExecutor 的 maxIterations
        // 护栏兜底死循环（§5.13）。末节点用条件边而非 plain edge→END，使末节点亦可自循环。
        for (int i = 0; i < nodes.size(); i++) {
            String self = nodes.get(i).name();
            String next = (i < nodes.size() - 1) ? nodes.get(i + 1).name() : StateGraph.END;
            graph.addConditionalEdges(self, edge.asAsyncEdgeAction(), Map.of(
                    GraphEdge.PROCEED_BRANCH, next,
                    GraphEdge.DEGRADE_BRANCH, next,
                    GraphEdge.SHORT_CIRCUIT_BRANCH, StateGraph.END,
                    GraphEdge.RETRY_BRANCH, self));
        }
        return graph;
    }
}
