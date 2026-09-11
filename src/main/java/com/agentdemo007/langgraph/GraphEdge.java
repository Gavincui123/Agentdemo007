package com.agentdemo007.langgraph;

import com.agentdemo007.common.pipeline.StepOutcome;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.state.AgentState;

/**
 * 条件分支边（Phase 14·LangGraph 条件路由）。
 *
 * <p>把上一步 {@link GraphNode} 发射到 langgraph4j 共享状态里的 {@link StepOutcome}（收口 sealed 类型）
 * 映射为 langgraph4j 条件边的分支键。langgraph4j {@code addConditionalEdges} 据 EdgeAction 返回值
 * 在路由表 {@code Map<String,String>} 中查目标节点——Proceed/Degrade 继续推进
 * （Degrade 仅标记不阻塞，§5.12），ShortCircuit 跳到 END（零 LLM 短路，①话术短路），
 * Retry 自循环回本节点（工具自纠正，§5.13，由 {@code maxIterations} 护栏兜底）。
 *
 * <p>分支键语义：
 * <ul>
 *   <li>{@link #PROCEED_BRANCH} — 推进下一节点。</li>
 *   <li>{@link #DEGRADE_BRANCH} — 推进下一节点（降级但继续；与 Proceed 同目标，分支键区分以便未来按场景精细化路由）。</li>
 *   <li>{@link #SHORT_CIRCUIT_BRANCH} — 跳到 END（短路，跳过后续节点）。</li>
 *   <li>{@link #RETRY_BRANCH} — 回到本节点（自循环重试，§5.13）。</li>
 * </ul>
 * 与 {@link GraphNode} 一样，outcome 解释（markDegraded/话术/审计）不在本类——
 * 由 {@code GraphExecutor} 集中收口（一模式一收口）。
 */
public class GraphEdge {

    public static final String PROCEED_BRANCH = "proceed";
    public static final String DEGRADE_BRANCH = "degrade";
    public static final String SHORT_CIRCUIT_BRANCH = "shortCircuit";
    public static final String RETRY_BRANCH = "retry";

    /** 适配为 langgraph4j 异步条件边动作：读 outcome → 返回分支键。 */
    public AsyncEdgeAction<AgentState> asAsyncEdgeAction() {
        return AsyncEdgeAction.edge_async(state -> {
            StepOutcome outcome = state.<StepOutcome>value(GraphNode.OUTCOME_KEY)
                    .orElseThrow(() -> new IllegalStateException(
                            "langgraph4j 状态缺少 StepOutcome（键=" + GraphNode.OUTCOME_KEY
                                    + "）；条件边须置于 GraphNode 之后"));
            if (outcome instanceof StepOutcome.Proceed) {
                return PROCEED_BRANCH;
            }
            if (outcome instanceof StepOutcome.ShortCircuit) {
                return SHORT_CIRCUIT_BRANCH;
            }
            if (outcome instanceof StepOutcome.Degrade) {
                return DEGRADE_BRANCH;
            }
            if (outcome instanceof StepOutcome.Retry) {
                return RETRY_BRANCH;
            }
            // sealed 四态已穷尽，此处理论不可达；保留兜底以满足编译器返回路径
            throw new IllegalStateException("未知 StepOutcome：" + outcome);
        });
    }
}
