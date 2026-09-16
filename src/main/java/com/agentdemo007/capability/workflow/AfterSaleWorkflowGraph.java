package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.business.OrderRecord;
import com.agentdemo007.capability.business.PolicyDomain;
import com.agentdemo007.capability.business.PolicyFragment;
import com.agentdemo007.capability.business.PolicyQueryService;
import com.agentdemo007.capability.business.UserQueryService;
import com.agentdemo007.capability.business.UserRecord;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 售后固定工作流子图（[[business-tools-workflow-dag]] §2.3·"将 DAG 确实落到 Workflow"·替 2 节点
 * {@link RefundWorkflowGraph} 为 5+ 节点真 DAG）。
 *
 * <p>return/refund 共用一个参数化图（D1 决策）：构造参数注入政策域 + 校验规则 + 提交服务。
 * 固定 6 节点 + 2 条件边：
 * <pre>
 * START → query_user → query_order → query_policy → validate
 *                                                       │ 条件边
 *                                          ┌────────────┴────────────┐
 *                                          ▼ pass                      ▼ fail
 *                                  submit_approval              END → Rejected(reason, message)
 *                                          │                     （校验失败按情况告知客户，不提交）
 *                                  approval_gate
 *                                          │ Approved→END / Denied→submit_approval(Retry) / Timeout→END
 * </pre>
 *
 * <p>节点委托底层 typed 服务（{@link UserQueryService}/{@link com.agentdemo007.capability.business.OrderQueryService}/
 * {@link PolicyQueryService}·单入口 seam）+ 校验规则（{@link AfterSaleValidationRule}，注入时钟）+
 * 提交 seam（{@link AfterSaleSubmitService}）+ 审批 seam（{@link WorkflowApprovalDecision}，复用）。
 * 非手撸 topo（铁律①：图结构用 langgraph4j {@link StateGraph}）。
 *
 * <p>终态 {@link AfterSaleWorkflowOutcome}（sealed）从终态读 {@code OUTCOME_KEY}：
 * validate fail→{@link AfterSaleWorkflowOutcome.Rejected}（E1：业务驳回≠系统失败，Slice 4 走 presetReply 短路）；
 * approval Approved→{@link AfterSaleWorkflowOutcome.Approved} / Denied(maxIterations)→{@link AfterSaleWorkflowOutcome.Denied}
 * / Timeout→{@link AfterSaleWorkflowOutcome.Timeout}。{@code maxIterations} 护栏防驳回死循环（镜像 P14）。
 *
 * <p>状态复用 {@link NoCloneStateSerializer} + {@link AgentState}，携 {@link PipelineContext}
 * （同 {@code GraphNode.CONTEXT_KEY} 模式：业务状态在 PipelineContext 强类型字段，不散落 Map）。
 * 节点/图异常 → 抛 {@link IllegalStateException} 由调用方（{@code WorkflowExecutionStep}，Slice 4）收口。
 */
public class AfterSaleWorkflowGraph implements AfterSaleWorkflow {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleWorkflowGraph.class);

    /** langgraph4j 共享状态中 PipelineContext 的键（镜像 {@code GraphNode.CONTEXT_KEY}）。 */
    static final String CONTEXT_KEY = "__pipelineContext__";
    /** query_order 节点写回的订单（Optional<OrderRecord>，召回空=Optional.empty()，供 validate 读）。 */
    static final String ORDER_KEY = "__order__";
    /** query_user 节点写回的用户（Optional<UserRecord>，供 submit 上下文用）。 */
    static final String USER_KEY = "__user__";
    /** query_policy 节点写回的政策片段（PolicyFragment，单 seam 政策源）。 */
    static final String POLICY_KEY = "__policy__";
    /** validate 节点写回的失败 Reason（仅失败时写入；缺=pass，供 validate 条件边路由）。 */
    static final String FAIL_KEY = "__validateFail__";
    /** approval_gate 节点写回的审批终态（供 approval 条件边路由）。 */
    static final String APPROVAL_KEY = "__approvalOutcome__";
    /** 终态：Rejected（validate fail）/ Approved / Denied / Timeout（approval），invoke 从终态读此键。 */
    static final String OUTCOME_KEY = "__afterSaleOutcome__";

    private static final String QUERY_USER_NODE = "query_user";
    private static final String QUERY_ORDER_NODE = "query_order";
    private static final String QUERY_POLICY_NODE = "query_policy";
    private static final String VALIDATE_NODE = "validate";
    private static final String SUBMIT_NODE = "submit_approval";
    private static final String APPROVAL_NODE = "approval_gate";

    // 驳回 Retry 死循环护栏（§5.13，镜像 P14/RefundWorkflowGraph）。本图 6 节点 + 4 节点线性前缀比
    // RefundWorkflowGraph（2 节点）大——langgraph4j 迭代预算按 generator yield 计（含节点执行 + 条件边/
    // 回边/END 终止开销，非纯节点数）：经实测 1 次 deny→retry→approve 即耗 ~10 yield（4 前缀 + submit/
    // approval×2 + 回边/END 开销）。故护栏须随图规模放大：20 允许 ~5 次 deny-retry（人工审批合理上限）
    // 后仍强制终止→收口 INTERNAL，保留"防死循环"原义不放宽到无意义。
    private static final int MAX_ITERATIONS = 20;
    private static final String PASS_BRANCH = "pass";
    private static final String FAIL_BRANCH = "fail";
    private static final String APPROVED_BRANCH = "approved";
    private static final String DENIED_BRANCH = "denied";
    private static final String TIMEOUT_BRANCH = "timeout";

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("(?i)ord-?\\d+");

    private final UserQueryService userService;
    private final com.agentdemo007.capability.business.OrderQueryService orderService;
    private final PolicyQueryService policyService;
    private final PolicyDomain policyDomain;
    private final AfterSaleValidationRule validationRule;
    private final AfterSaleSubmitService submitService;
    private final WorkflowApprovalDecision approvalDecision;
    /** 当前账户 id（mock 固定 10086；真接入后由 auth/session per-request 注入——已知限制，迭代后补）。 */
    private final String currentUserId;

    public AfterSaleWorkflowGraph(UserQueryService userService,
                                   com.agentdemo007.capability.business.OrderQueryService orderService,
                                   PolicyQueryService policyService,
                                   PolicyDomain policyDomain,
                                   AfterSaleValidationRule validationRule,
                                   AfterSaleSubmitService submitService,
                                   WorkflowApprovalDecision approvalDecision,
                                   String currentUserId) {
        this.userService = userService;
        this.orderService = orderService;
        this.policyService = policyService;
        this.policyDomain = policyDomain;
        this.validationRule = validationRule;
        this.submitService = submitService;
        this.approvalDecision = approvalDecision;
        this.currentUserId = currentUserId;
    }

    /**
     * 驱动售后固定工作流子图（同步阻塞至 END）并返回终态。
     *
     * <p>validate fail→{@link AfterSaleWorkflowOutcome.Rejected}（不提交）；pass→submit→approval→
     * Approved/Denied(maxIterations)/Timeout。产出写 {@code context.workflowResult}（submit 节点）；
     * 终态 {@link AfterSaleWorkflowOutcome} 供调用方（{@code WorkflowExecutionStep}，Slice 4）按终态收口。
     * 异常抛 {@link IllegalStateException} 供调用方收口为 {@code ShortCircuit(INTERNAL)}。
     */
    public AfterSaleWorkflowOutcome invoke(PipelineContext context) {
        StateGraph<AgentState> graph;
        try {
            graph = buildGraph();
        } catch (GraphStateException e) {
            throw new IllegalStateException("售后工作流图定义构建失败: " + e.getMessage(), e);
        }
        try {
            CompiledGraph<AgentState> compiled = graph.compile();
            compiled.setMaxIterations(MAX_ITERATIONS);
            Optional<AgentState> finalState = compiled.invoke(Map.of(CONTEXT_KEY, context));
            return finalState
                    .orElseThrow(() -> new IllegalStateException("售后工作流未产出终态"))
                    .<AfterSaleWorkflowOutcome>value(OUTCOME_KEY)
                    .orElseThrow(() -> new IllegalStateException("工作流终态缺少售后结果（键=" + OUTCOME_KEY + "）"));
        } catch (Exception e) { // compile 抛 GraphStateException（受检）、invoke 抛运行时，统收
            throw new IllegalStateException("售后工作流图执行失败: " + e.getMessage(), e);
        }
    }

    private StateGraph<AgentState> buildGraph() throws GraphStateException {
        StateGraph<AgentState> graph = new StateGraph<>(Map.of(),
                new NoCloneStateSerializer(AgentState::new));

        graph.addNode(QUERY_USER_NODE, AsyncNodeAction.node_async(state -> {
            PipelineContext ctx = requireContext(state);
            Optional<UserRecord> user = userService.findByUserId(resolveUserId(ctx));
            return Map.of(USER_KEY, user);
        }));

        graph.addNode(QUERY_ORDER_NODE, AsyncNodeAction.node_async(state -> {
            PipelineContext ctx = requireContext(state);
            String orderId = extractOrderId(ctx);
            Optional<OrderRecord> order = orderService.findByOrderId(orderId);
            return Map.of(ORDER_KEY, order); // Optional（召回空=empty，validate 读 null→ORDER_NOT_FOUND）
        }));

        graph.addNode(QUERY_POLICY_NODE, AsyncNodeAction.node_async(state -> {
            // 单入口 seam 政策源（复用 @Tool 同一 PolicyQueryService，决策 R）
            PolicyFragment policy = policyService.query(policyDomain);
            return Map.of(POLICY_KEY, policy);
        }));

        graph.addNode(VALIDATE_NODE, AsyncNodeAction.node_async(state -> {
            PipelineContext ctx = requireContext(state);
            Optional<OrderRecord> orderOpt = state.<Optional<OrderRecord>>value(ORDER_KEY)
                    .orElse(Optional.empty());
            OrderRecord order = orderOpt.orElse(null);
            Optional<Reason> fail = validationRule.validate(order, resolveUserId(ctx));
            if (fail.isPresent()) {
                AfterSaleWorkflowOutcome.Rejected rejected =
                        new AfterSaleWorkflowOutcome.Rejected(fail.get(), rejectionMessage(fail.get(), ctx));
                return Map.of(FAIL_KEY, fail.get(), OUTCOME_KEY, rejected);
            }
            return Map.of(); // pass：不写 FAIL_KEY（条件边见缺=pass）
        }));

        graph.addNode(SUBMIT_NODE, AsyncNodeAction.node_async(state -> {
            PipelineContext ctx = requireContext(state);
            String workflowResult = submitService.submit(ctx);
            ctx.setWorkflowResult(workflowResult);
            return Map.of();
        }));

        graph.addNode(APPROVAL_NODE, AsyncNodeAction.node_async(state -> {
            PipelineContext ctx = requireContext(state);
            Optional<OrderRecord> orderOpt = state.<Optional<OrderRecord>>value(ORDER_KEY).orElse(Optional.empty());
            Optional<PolicyFragment> policyOpt = state.<PolicyFragment>value(POLICY_KEY);
            String orderStatus = orderOpt.map(OrderRecord::status).orElse(null);
            String policyConclusion = policyOpt.map(PolicyFragment::text).orElse(null);
            WorkflowApprovalDecision.Outcome ao = approvalDecision.await(ctx.workflowResult());
            AfterSaleWorkflowOutcome outcome = translate(ao, orderStatus, policyConclusion);
            return Map.of(APPROVAL_KEY, ao, OUTCOME_KEY, outcome);
        }));

        // 固定边：START→query_user→query_order→query_policy→validate
        graph.addEdge(StateGraph.START, QUERY_USER_NODE);
        graph.addEdge(QUERY_USER_NODE, QUERY_ORDER_NODE);
        graph.addEdge(QUERY_ORDER_NODE, QUERY_POLICY_NODE);
        graph.addEdge(QUERY_POLICY_NODE, VALIDATE_NODE);

        // validate 条件边：pass→submit_approval；fail→END（Rejected 已写 OUTCOME_KEY）
        graph.addConditionalEdges(VALIDATE_NODE,
                AsyncEdgeAction.edge_async(state -> {
                    Optional<Reason> fail = state.<Reason>value(FAIL_KEY);
                    return fail.isPresent() ? FAIL_BRANCH : PASS_BRANCH;
                }),
                Map.of(PASS_BRANCH, SUBMIT_NODE, FAIL_BRANCH, StateGraph.END));

        // submit_approval→approval_gate
        graph.addEdge(SUBMIT_NODE, APPROVAL_NODE);

        // approval 条件边：Approved→END；Denied→submit_approval（Retry 改写重提）；Timeout→END
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

    /** 翻译审批终态 → 售后工作流终态（Approved/Denied/Timeout 三态对齐），富集订单状态+政策结论。 */
    private AfterSaleWorkflowOutcome translate(WorkflowApprovalDecision.Outcome ao, String orderStatus, String policyConclusion) {
        if (ao instanceof WorkflowApprovalDecision.Approved a) {
            return new AfterSaleWorkflowOutcome.Approved(a.approver(), orderStatus, policyConclusion);
        }
        if (ao instanceof WorkflowApprovalDecision.Denied d) {
            return new AfterSaleWorkflowOutcome.Denied(d.reason());
        }
        if (ao instanceof WorkflowApprovalDecision.Timeout) {
            return new AfterSaleWorkflowOutcome.Timeout();
        }
        throw new IllegalStateException("未知审批终态: " + ao);
    }

    /** 校验失败客户话术（按 {@link Reason} 分情况告知，用户钦定"校验失败按情况告知客户"）。 */
    private String rejectionMessage(Reason reason, PipelineContext ctx) {
        String oid = nullToEmpty(extractOrderId(ctx));
        return switch (reason) {
            case ORDER_NOT_FOUND -> "未查询到订单 " + oid + "，请确认订单号是否正确后重新申请。";
            case ORDER_NOT_OWNED -> "订单 " + oid + " 不属于当前账户，无法代为办理退货/退款。";
            case BEYOND_7_DAY -> "订单 " + oid + " 已超过7天无理由退货期限，如遇质量问题请联系人工客服。";
            case REFUND_WINDOW_EXPIRED -> "订单 " + oid + " 已超过退款办理期限，无法退款。";
        };
    }

    /**
     * 从文本提取首个 {@code ORD-\d+} 订单号（mock 期文本提取；真接入后由 RoutePlan required_entity 注入）。
     * 包级静态——供 {@link WorkflowExecutionStep} entity-gate 复用同一提取契约（DRY，单点演进）。
     */
    static String extractOrderIdFrom(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = ORDER_ID_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** 从 rawInput 提取首个 {@code ORD-\d+} 订单号（DAG query_order 节点用）。 */
    private String extractOrderId(PipelineContext ctx) {
        return extractOrderIdFrom(ctx.rawInput());
    }

    /**
     * 解析当前用户 id：per-request {@code ctx.userId()}（前端 ChatRequest 传入）优先；null/空白兜底图 baked
     * {@code currentUserId}（mock 10086，eval/未传鉴权场景）。[[business-tools-workflow-dag]] 真接入：前端传 userId→
     * 校验对真实用户做；迭代后真鉴权强制非空（兜底退役）。
     */
    private String resolveUserId(PipelineContext ctx) {
        String uid = ctx.userId();
        return (uid != null && !uid.isBlank()) ? uid : currentUserId;
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private PipelineContext requireContext(AgentState state) {
        return state.<PipelineContext>value(CONTEXT_KEY)
                .orElseThrow(() -> new IllegalStateException("工作流状态缺少 PipelineContext（键=" + CONTEXT_KEY + "）"));
    }
}
