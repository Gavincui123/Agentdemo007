package com.agentdemo007.capability.workflow;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.langgraph.NoCloneStateSerializer;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

/**
 * 退款固定工作流子图（高风险固定工作流·[[per-intent-dag]]·LangGraph 预定义图，非每请求动态生成）。
 *
 * <p>两节点固定子图（不复用顶层 {@code AgentStateGraph} 线性包装——那是 step 列表编译为线性链；本图是
 * 独立 2 节点带条件回边的真分支图）：
 * <ul>
 *   <li>{@code submit_refund} → {@link RefundService#submit} 写 {@code context.workflowResult}；</li>
 *   <li>{@code approval_gate} → {@link WorkflowApprovalDecision#await} 产审批终态 → 条件边路由。</li>
 * </ul>
 * 边：{@code START→submit→approval}；approval 条件边 {@code Approved→END}、
 * {@code Denied→submit}（Retry 改写重提）、{@code Timeout→END}。{@code maxIterations} 护栏防驳回死循环（镜像 P14）。
 *
 * <p>状态复用 {@link NoCloneStateSerializer} + {@link AgentState}，携 {@link PipelineContext}
 * （同 {@code GraphNode.CONTEXT_KEY} 模式：业务状态在 PipelineContext 强类型字段，不散落 Map）。
 * 节点/图异常 → 抛 {@link IllegalStateException} 由调用方（{@code WorkflowExecutionStep}，Slice 3）
 * 收口为 {@code ShortCircuit(INTERNAL)}。本类只产"图定义 + 驱动"，不收口终态（④由顶层 executor）。
 */
public class RefundWorkflowGraph {

    private static final Logger log = LoggerFactory.getLogger(RefundWorkflowGraph.class);

    /** langgraph4j 共享状态中 PipelineContext 的键（镜像 {@code GraphNode.CONTEXT_KEY}）。 */
    static final String CONTEXT_KEY = "__pipelineContext__";
    /** approval_gate 节点发射到状态的审批终态键（条件边据此路由）。 */
    static final String APPROVAL_KEY = "__approvalOutcome__";

    private static final String SUBMIT_NODE = "submit_refund";
    private static final String APPROVAL_NODE = "approval_gate";
    private static final int MAX_ITERATIONS = 10; // 驳回 Retry 死循环护栏（§5.13）
    private static final String APPROVED_BRANCH = "approved";
    private static final String DENIED_BRANCH = "denied";
    private static final String TIMEOUT_BRANCH = "timeout";

    private final RefundService refundService;
    private final WorkflowApprovalDecision approvalDecision;

    public RefundWorkflowGraph(RefundService refundService, WorkflowApprovalDecision approvalDecision) {
        this.refundService = refundService;
        this.approvalDecision = approvalDecision;
    }

    /**
     * 驱动固定工作流子图（同步阻塞至 END）并返回审批终态。submit→approval→(approved)→END；
     * denied→回 submit 改写重提；timeout→END。产出写 {@code context.workflowResult}；
     * 返回 approval_gate 最终审批终态（{@link WorkflowApprovalDecision.Outcome}）供调用方
     * ({@code WorkflowExecutionStep}) 按终态收口（Approved→Proceed、Timeout→ShortCircuit）。
     * 异常抛 {@link IllegalStateException} 供调用方收口为 {@code ShortCircuit(INTERNAL)}。
     */
    public WorkflowApprovalDecision.Outcome invoke(PipelineContext context) {
        StateGraph<AgentState> graph;
        try {
            graph = buildGraph();
        } catch (GraphStateException e) {
            throw new IllegalStateException("退款工作流图定义构建失败: " + e.getMessage(), e);
        }
        try {
            CompiledGraph<AgentState> compiled = graph.compile();
            compiled.setMaxIterations(MAX_ITERATIONS);
            Optional<AgentState> finalState = compiled.invoke(Map.of(CONTEXT_KEY, context));
            // Slice 4：从终态读 approval_gate 写入的审批终态（Approved/Timeout 经条件边到 END；
            // Denied 终态=maxIterations 强制终止，仍读最后审批结果）。供调用方按终态收口。
            return finalState
                    .orElseThrow(() -> new IllegalStateException("退款工作流未产出终态"))
                    .<WorkflowApprovalDecision.Outcome>value(APPROVAL_KEY)
                    .orElseThrow(() -> new IllegalStateException("工作流终态缺少审批结果（键=" + APPROVAL_KEY + "）"));
        } catch (Exception e) { // compile 抛 GraphStateException（受检）、invoke 抛运行时，统收
            throw new IllegalStateException("退款工作流图执行失败: " + e.getMessage(), e);
        }
    }

    private StateGraph<AgentState> buildGraph() throws GraphStateException {
        StateGraph<AgentState> graph = new StateGraph<>(Map.of(),
                new NoCloneStateSerializer(AgentState::new));

        graph.addNode(SUBMIT_NODE, AsyncNodeAction.node_async(state -> {
            PipelineContext ctx = requireContext(state);
            String workflowResult = refundService.submit(ctx);
            ctx.setWorkflowResult(workflowResult);
            return Map.of();
        }));
        graph.addNode(APPROVAL_NODE, AsyncNodeAction.node_async(state -> {
            PipelineContext ctx = requireContext(state);
            WorkflowApprovalDecision.Outcome outcome = approvalDecision.await(ctx.workflowResult());
            return Map.of(APPROVAL_KEY, outcome);
        }));

        graph.addEdge(StateGraph.START, SUBMIT_NODE);
        graph.addEdge(SUBMIT_NODE, APPROVAL_NODE);
        graph.addConditionalEdges(APPROVAL_NODE,
                AsyncEdgeAction.edge_async(state -> {
                    WorkflowApprovalDecision.Outcome o = state.<WorkflowApprovalDecision.Outcome>value(APPROVAL_KEY)
                            .orElseThrow(() -> new IllegalStateException("工作流状态缺少审批终态（键=" + APPROVAL_KEY + "）"));
                    if (o instanceof WorkflowApprovalDecision.Approved) {
                        return APPROVED_BRANCH;
                    }
                    if (o instanceof WorkflowApprovalDecision.Denied) {
                        return DENIED_BRANCH;
                    }
                    if (o instanceof WorkflowApprovalDecision.Timeout) {
                        return TIMEOUT_BRANCH;
                    }
                    throw new IllegalStateException("未知审批终态: " + o);
                }),
                Map.of(APPROVED_BRANCH, StateGraph.END,
                        DENIED_BRANCH, SUBMIT_NODE, // Retry：驳回回 submit 改写重提
                        TIMEOUT_BRANCH, StateGraph.END));

        return graph;
    }

    private PipelineContext requireContext(AgentState state) {
        return state.<PipelineContext>value(CONTEXT_KEY)
                .orElseThrow(() -> new IllegalStateException("工作流状态缺少 PipelineContext（键=" + CONTEXT_KEY + "）"));
    }
}
