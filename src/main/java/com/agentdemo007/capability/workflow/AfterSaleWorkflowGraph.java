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
 * 售后固定工作流子图（[[business-tools-workflow-dag]] §2.3·"将 DAG 确实落到 Workflow"·
 * 替旧 2 节点 RefundWorkflowGraph（已退役删除）为真 DAG·2026-09-20 提交制收缩为 5 节点）。
 *
 * <p>return/refund 共用一个参数化图（D1 决策）：构造参数注入政策域 + 资格裁决器 + 工单提交器。
 * 固定 5 节点（<b>原则（用户裁决）：审批是事件、Agent 最小权限</b>——建单即返回，无请求内等待）：
 * <pre>
 * START → query_user → query_order → query_policy → validate（机械校验 + Agent 资格裁决）
 *                                                       │ fail
 *                                          ┌────────────┴────────────┐
 *                                          ▼ pass                     ▼ END
 *                                  submit_ticket（建人工工单即返回）  Rejected(reason, message)
 *                                          │                 （校验失败按情况告知客户，不建单）
 *                                          ▼ END（Pending）
 * </pre>
 *
 * <p>节点委托底层 typed 服务（{@link UserQueryService}/{@link com.agentdemo007.capability.business.OrderQueryService}/
 * {@link PolicyQueryService}·单入口 seam）+ <b>Agent 资格裁决</b>（{@link AfterSaleEligibilityJudge}：
 * 政策知识 + 订单实时事实一并交模型，2026-09-19 用户裁决退役硬编码窗口规则；订单存在/归属的机械
 * 事实校验保留代码内）+ 工单提交 seam（{@link WorkflowApprovalSubmitter}：建 PENDING 人工工单后
 * <b>立即返回</b>；人工批准/驳回是管理台的工单状态事件，业务执行属业务系统内部运作
 * {@link AfterSaleBusinessExecutor}，客户后续经工单查询工具读进度）。
 * 非手撸 topo（铁律①：图结构用 langgraph4j {@link StateGraph}）。
 *
 * <p>终态 {@link AfterSaleWorkflowOutcome}（sealed）从终态读 {@code OUTCOME_KEY}：
 * validate fail→{@link AfterSaleWorkflowOutcome.Rejected}（E1：业务驳回≠系统失败，走 presetReply 短路）；
 * pass→submit_ticket→{@link AfterSaleWorkflowOutcome.Pending}（提交制终态）。
 * {@code maxIterations} 护栏防死循环保留（镜像 P14）。
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
    /** validate 节点写回的 Agent 资格裁决（pass 时写入，供 approval_gate 进工单展示审批依据）。 */
    static final String VERDICT_KEY = "__eligibilityVerdict__";
    /** approval（submit_ticket）节点写回的提交结果（供审计/扩展读）。 */
    static final String APPROVAL_KEY = "__approvalOutcome__";
    /** 终态：Rejected（validate fail）/ Pending（提交制，工单已受理）——invoke 从终态读此键。 */
    static final String OUTCOME_KEY = "__afterSaleOutcome__";

    private static final String QUERY_USER_NODE = "query_user";
    private static final String QUERY_ORDER_NODE = "query_order";
    private static final String QUERY_POLICY_NODE = "query_policy";
    private static final String VALIDATE_NODE = "validate";
    private static final String SUBMIT_TICKET_NODE = "submit_ticket";

    // 防死循环护栏（§5.13，镜像 P14）。生产化后人工驳回已改终局（不再 Retry），护栏仅兜底
    // 异常重提场景；langgraph4j 迭代预算按 generator yield 计（含节点执行+边/END 开销），20 足够。
    private static final int MAX_ITERATIONS = 20;
    private static final String PASS_BRANCH = "pass";
    private static final String FAIL_BRANCH = "fail";

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("(?i)ord-?\\d+");

    private final UserQueryService userService;
    private final com.agentdemo007.capability.business.OrderQueryService orderService;
    private final PolicyQueryService policyService;
    private final PolicyDomain policyDomain;
    /** Agent 资格裁决器（政策知识+实时事实→三态裁决；生产化 2026-09-19 替代硬编码窗口规则）。 */
    private final AfterSaleEligibilityJudge eligibilityJudge;
    /** 工单提交器（提交制 2026-09-20：建 PENDING 人工工单即返回，无请求内等待）。 */
    private final WorkflowApprovalSubmitter approvalSubmitter;
    /** 当前账户 id（mock 固定 10086；真接入后由 auth/session per-request 注入——已知限制，迭代后补）。 */
    private final String currentUserId;

    public AfterSaleWorkflowGraph(UserQueryService userService,
                                   com.agentdemo007.capability.business.OrderQueryService orderService,
                                   PolicyQueryService policyService,
                                   PolicyDomain policyDomain,
                                   AfterSaleEligibilityJudge eligibilityJudge,
                                   WorkflowApprovalSubmitter approvalSubmitter,
                                   String currentUserId) {
        this.userService = userService;
        this.orderService = orderService;
        this.policyService = policyService;
        this.policyDomain = policyDomain;
        this.eligibilityJudge = eligibilityJudge;
        this.approvalSubmitter = approvalSubmitter;
        this.currentUserId = currentUserId;
    }

    /**
     * 驱动售后固定工作流子图（同步阻塞至 END）并返回终态。
     *
     * <p>validate fail→{@link AfterSaleWorkflowOutcome.Rejected}（不建单）；pass→submit_ticket
     * 建人工工单即返回→{@link AfterSaleWorkflowOutcome.Pending}（提交制：无请求内等待）。
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
            // 单入口 seam 政策源（复用 @Tool 同一 PolicyQueryService，决策 R）；
            // 检索词 = standardQuery（缺失回退 rawInput）——真 RAG 政策源需用户问题原词检索（v5 seam 扩展）。
            // Phase 21 等级门：显式传请求主体（ctx.userId/memberLevel，图线程不依赖 ThreadLocal）——
            // 不用 resolveUserId 兜底（baked 10086 会把匿名灌成 V5 fail-open），匿名按 V0 fail-closed。
            PipelineContext ctx = requireContext(state);
            PolicyFragment policy = policyService.query(policyDomain, resolveQuery(ctx),
                    com.agentdemo007.session.ChatSubject.of(ctx.userId(), ctx.memberLevel()));
            return Map.of(POLICY_KEY, policy);
        }));

        graph.addNode(VALIDATE_NODE, AsyncNodeAction.node_async(state -> {
            PipelineContext ctx = requireContext(state);
            Optional<OrderRecord> orderOpt = state.<Optional<OrderRecord>>value(ORDER_KEY)
                    .orElse(Optional.empty());
            OrderRecord order = orderOpt.orElse(null);
            // 机械事实校验（存在/归属——非政策判断，保留代码内，短路即驳）
            if (order == null) {
                Reason fail = Reason.ORDER_NOT_FOUND;
                return Map.of(FAIL_KEY, fail,
                        OUTCOME_KEY, (AfterSaleWorkflowOutcome) new AfterSaleWorkflowOutcome.Rejected(
                                fail, rejectionMessage(fail, ctx)));
            }
            String uid = resolveUserId(ctx);
            if (uid != null && !uid.equals(order.userId())) {
                Reason fail = Reason.ORDER_NOT_OWNED;
                return Map.of(FAIL_KEY, fail,
                        OUTCOME_KEY, (AfterSaleWorkflowOutcome) new AfterSaleWorkflowOutcome.Rejected(
                                fail, rejectionMessage(fail, ctx)));
            }
            // 政策资格 → Agent 裁决（2026-09-19 用户裁决）：政策知识（query_policy 召回）+ 实时事实
            //（订单记录 + 当前日期）一并交模型三态裁决；裁决器内部全降级（失败=UNCERTAIN fail-safe
            // 到人工，绝不冒充业务驳回、绝不盲目放行）
            PolicyFragment policy = state.<PolicyFragment>value(POLICY_KEY).orElse(null);
            AfterSaleEligibilityJudge.Verdict verdict = eligibilityJudge.judge(
                    new AfterSaleEligibilityJudge.EligibilityInput(
                            policyDomain.name(), order, policy, resolveQuery(ctx), uid));
            if (verdict.decision() == AfterSaleEligibilityJudge.Decision.INELIGIBLE) {
                Reason fail = Reason.POLICY_INELIGIBLE;
                String message = (verdict.customerMessage() != null && !verdict.customerMessage().isBlank())
                        ? verdict.customerMessage()
                        : rejectionMessage(fail, ctx);
                log.info("Agent 资格裁决驳回：sessionId={} action={} orderId={} basis={}",
                        ctx.sessionId(), policyDomain, order.orderId(), verdict.basis());
                return Map.of(FAIL_KEY, fail,
                        OUTCOME_KEY, (AfterSaleWorkflowOutcome) new AfterSaleWorkflowOutcome.Rejected(fail, message));
            }
            return Map.of(VERDICT_KEY, verdict); // pass（ELIGIBLE/UNCERTAIN 均进人工审批，UNCERTAIN 管理员重点复核）
        }));

        graph.addNode(SUBMIT_TICKET_NODE, AsyncNodeAction.node_async(state -> {
            PipelineContext ctx = requireContext(state);
            Optional<OrderRecord> orderOpt = state.<Optional<OrderRecord>>value(ORDER_KEY).orElse(Optional.empty());
            Optional<PolicyFragment> policyOpt = state.<PolicyFragment>value(POLICY_KEY);
            // 兜底话术不是政策结论：RAG 终闸全灭时 query_policy 返回 FALLBACK（source=兜底政策），
            // 拼进工单审批依据会误导管理员——按 null 丢弃。
            String policyConclusion = policyOpt
                    .filter(p -> !"兜底政策".equals(p.source()))
                    .map(PolicyFragment::text)
                    .orElse(null);
            // 提交制（2026-09-20 原则翻转）：工单上下文全量富集（管理员一屏可审"审什么、凭什么"），
            // 建单即返回——批准/驳回是管理台的工单状态事件，客户后续经工单查询工具获知进度。
            String verdictNote = state.<AfterSaleEligibilityJudge.Verdict>value(VERDICT_KEY)
                    .map(v -> v.decision() + "｜依据：" + v.basis())
                    .orElse(null);
            WorkflowApprovalSubmitter.ApprovalRequest request = new WorkflowApprovalSubmitter.ApprovalRequest(
                    ctx.sessionId(), policyDomain.name(), extractOrderId(ctx),
                    orderOpt.map(AfterSaleWorkflowGraph::summarizeOrder).orElse(null),
                    policyConclusion, verdictNote, resolveQuery(ctx));
            WorkflowApprovalSubmitter.Outcome submitted = approvalSubmitter.submit(request);
            return Map.of(APPROVAL_KEY, submitted, OUTCOME_KEY,
                    (AfterSaleWorkflowOutcome) new AfterSaleWorkflowOutcome.Pending(submitted.alreadyApproved()));
        }));

        // 固定边：START→query_user→query_order→query_policy→validate
        graph.addEdge(StateGraph.START, QUERY_USER_NODE);
        graph.addEdge(QUERY_USER_NODE, QUERY_ORDER_NODE);
        graph.addEdge(QUERY_ORDER_NODE, QUERY_POLICY_NODE);
        graph.addEdge(QUERY_POLICY_NODE, VALIDATE_NODE);

        // validate 条件边：pass→submit_ticket；fail→END（Rejected 已写 OUTCOME_KEY）
        graph.addConditionalEdges(VALIDATE_NODE,
                AsyncEdgeAction.edge_async(state -> {
                    Optional<Reason> fail = state.<Reason>value(FAIL_KEY);
                    return fail.isPresent() ? FAIL_BRANCH : PASS_BRANCH;
                }),
                Map.of(PASS_BRANCH, SUBMIT_TICKET_NODE, FAIL_BRANCH, StateGraph.END));

        // submit_ticket→END（提交制终局：Pending；决议属管理台事件，不在图内）
        graph.addEdge(SUBMIT_TICKET_NODE, StateGraph.END);

        return graph;
    }

    /** 机械校验失败客户话术（订单不存在/非本人；政策类驳回话术由 Agent 裁决产出，不经此）。 */
    private String rejectionMessage(Reason reason, PipelineContext ctx) {
        String oid = nullToEmpty(extractOrderId(ctx));
        return switch (reason) {
            case ORDER_NOT_FOUND -> "未查询到订单 " + oid + "，请确认订单号是否正确后重新申请。";
            case ORDER_NOT_OWNED -> "订单 " + oid + " 不属于当前账户，无法代为办理退货/退款。";
            case POLICY_INELIGIBLE -> "订单 " + oid + " 按平台政策不符合办理条件，如有疑问请联系人工客服。";
        };
    }

    /** 订单实时事实摘要（审批工单展示用：管理员一屏可审"审什么"）。 */
    private static String summarizeOrder(OrderRecord order) {
        return "用户 " + order.userId()
                + "，下单 " + order.orderTime()
                + "，状态 " + order.status()
                + "，商品" + order.items()
                + "，金额 " + order.amount();
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
        // 归一化大写：(?i) 只管匹配，group() 原样返回——实测小写 "ord-001" 原样进查找，
        // OrderQueryService 精确键 miss → 误判 ORDER_NOT_FOUND「未查询到订单」。
        return m.find() ? m.group().toUpperCase(java.util.Locale.ROOT) : null;
    }

    /**
     * 订单号提取（<b>决策层接短期记忆</b>·2026-09-18）：{@code rawInput} 原话优先，缺失回退
     * {@code standardQuery}——改写层结合会话历史补全的产物（第 4 轮"我要退款"原话无单号，
     * 改写已把 ORD-001 补进 standardQuery；只读 raw 即记忆盲区 → 重复澄清的根因）。
     * public——供 {@code WorkflowExecutionStep} entity-gate 与 {@code RoutePlanStep} 能力收敛共用
     * 同一提取契约（单点演进；routePlan 契约据"有无订单号"裁定工具/RAG 取舍）。
     */
    public static String extractOrderIdFrom(PipelineContext context) {
        if (context == null) {
            return null;
        }
        String fromRaw = extractOrderIdFrom(context.rawInput());
        if (fromRaw != null) {
            return fromRaw;
        }
        var sq = context.standardQuery();
        return (sq != null) ? extractOrderIdFrom(sq.text()) : null;
    }

    /**
     * 解析检索词：{@code standardQuery.text()}（改写后查询）优先，缺失回退 {@code rawInput} 原词——
     * 与 {@code RagStep}/{@code ToolExecutionStep} 同口径。
     */
    private String resolveQuery(PipelineContext ctx) {
        var sq = ctx.standardQuery();
        return (sq != null) ? sq.text() : ctx.rawInput();
    }

    private String extractOrderId(PipelineContext ctx) {
        return extractOrderIdFrom(ctx); // raw → standardQuery（决策层接短期记忆）
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
