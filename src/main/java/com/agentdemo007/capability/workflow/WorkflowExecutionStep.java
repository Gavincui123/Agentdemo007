package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.capability.plan.RoutePlanBaselines;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.ConcurrentReply;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.context.SystemPromptAssembler;
import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.session.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/**
 * 高风险固定工作流触发步（第四层·{@code @Order(670)}，紧随 {@code RagStep(660)}、先于 {@code ContextBuilder(700)}）。
 *
 * <p><b>@670 状态机</b>（[[p0-intent-switch-clarify-design]] §5；2026-09-18 增 1b 衔接判定）：按当前轮
 * {@link RoutePlan} 的 intent/ambiguous/secondaryIntent + 订单号（raw 优先、缺失回退 standardQuery
 * ——决策层接短期记忆）+ 提交业务记忆 + pending 状态，决策续跑/切换/放弃/并发。
 * ambiguous 最先短路（澄清菜单，pending 保留）；<b>1b 衔接判定</b>：同 action 同订单（或同会话最近
 * 同 action 且本轮无单号）的活跃提交 → 回"已在处理中"，不重复澄清、不重复提单；无单号
 * workflow→澄清存 pending；单号+workflow→先 remove 再跑图；单号+abandon 集
 * （order_query/security_request/...）→先 remove 主链；单号+非售后+pending→并发（Task 14）。
 *
 * <p><b>entity-gate 澄清</b>（用户钦定"没提供订单号应先澄清，不直接跑工作流/返政策"）：requiresWorkflow=true 但
 * rawInput 无订单号 → {@code context.setPresetReply(澄清话术)} + {@code pendingStore.put(sessionId, PendingWorkflow(intent))}
 * + Proceed（图不调）。<b>澄清话术由大模型生成</b>（2026-09-17 用户裁决定案）：面向模型的指令 =
 * {@code system-prompt-segments} 片段按意图装配 + 澄清任务指令，经 {@link ChatLlmService#chatRaw} 出站
 * （scene=澄清话术，进「LLM出站」日志）；LLM 不可用/空回复/本步未装配 LLM → 硬编码话术 ②降级兜底。
 * presetReply 仍经 {@link com.agentdemo007.output.OutputStep} 守卫短路（生成只发生一次，输出端不再二次调 LLM）。
 * Turn 2 由本步状态机按当前轮 routePlan 决策续跑/切换/放弃。
 *
 * <p><b>return/refund 流选择</b>（D1 参数化图）：{@code "return_request".equals(rp.intent())} → 退货图
 * （{@link PolicyDomain#RETURN}），否则（refund_request）→ 退款图（{@link PolicyDomain#REFUND}）。
 * 两图均 {@link AfterSaleWorkflow} seam，由 {@link WorkflowConfig} 按属性门控装配
 * （提交制：Agent 资格裁决 {@link LlmAfterSaleEligibilityJudge} + 工单提交
 * {@link TicketApprovalSubmitter}，2026-09-20 用户裁决：审批是事件、Agent 最小权限）。
 *
 * <p>属性门控 {@code app.workflow.enabled}（缺省 false）→ 本 bean 不装配，@670 槽空，
 * 旧链 {@code RagStep(660)→ContextBuilder(700)} 线性等价不变；enabled=true 时装配。
 *
 * <p><b>终态收口（④统一收口，[[business-tools-workflow-dag]] §2.4·E1·2026-09-20 提交制收缩）</b>——
 * 图返 {@link AfterSaleWorkflowOutcome}（sealed 两态），本步按终态映射（图<b>不直接产 {@code PipelineResult}</b>，
 * 写 context，终态由顶层 {@code PipelineExecutor} 收口）：
 * <ul>
 *   <li>{@link AfterSaleWorkflowOutcome.Rejected} → {@code context.setPresetReply(message)} +
 *       {@link StepOutcome.Proceed}（<b>业务驳回≠系统失败</b>，E1：不 ShortCircuit、不混
 *       {@link DegradationScenario}；话术走 presetReply 短路）；</li>
 *   <li>{@link AfterSaleWorkflowOutcome.Pending} → 登记提交记忆（重复请求回"处理中"）+
 *       {@code context.setPresetReply(已提交人工审批话术)} + {@link StepOutcome.Proceed}
 *       （提交制 2026-09-20：建工单即返回，<b>无请求内等待</b>——批准/驳回是管理台的工单状态事件，
 *       不受任何超时影响；同键此前已批准回"此前已通过"话术，绝不重复建单）。</li>
 * </ul>
 * 客户对审批结果的感知：后续轮次经工单状态查询工具（{@code TicketStatusQueryTool}）读工单进度。
 */
@Component
@Order(670)
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowExecutionStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionStep.class);

    /** abandon 集：单号 + 这些 intent → 先 remove pending + 主链（不跑图，当前轮优先）。 */
    private static final Set<String> ABANDON_SET = Set.of(
            "order_query", "refund_status_query", "security_request",
            "degradation_request", "low_confidence_query");

    /** 并发腿1 优雅话术（await 超时/异常兜底，§7.4）。OutputStep.awaitLeg1 跨包引用，须 public。 */
    public static final String WORKFLOW_PENDING_TEXT = "您的售后申请已受理，正在人工审批中，请稍后查询进度。";

    /** 退款流图（refund_request → PolicyDomain.REFUND + Agent 资格裁决 + 真工单审批）。 */
    private final AfterSaleWorkflow refundWorkflow;
    /** 退货流图（return_request → PolicyDomain.RETURN + Agent 资格裁决 + 真工单审批）。 */
    private final AfterSaleWorkflow returnWorkflow;
    /** 待续跑工作流存储（[[business-tools-workflow-dag]] §2.5·Turn 1 澄清存 intent，Turn 2 续跑）。 */
    private final PendingWorkflowStore pendingStore;
    /**
     * 分段式系统提示词装配器（澄清话术生成的<b>面向模型的指令</b>来源：{@code system-prompt-segments}
     * 片段按意图装配；null→不生成，直接硬编码兜底）。
     */
    private final SystemPromptAssembler promptAssembler;
    /** LLM 门面（澄清话术生成出口，scene=澄清话术；null→不生成，直接硬编码兜底）。 */
    private final ChatLlmService llm;
    /** 并发执行器（Task 14 真并发用；单腿/占位阶段=Runnable::run 同步）。 */
    private final Executor executor;
    /** 子管线 runner（Task 14 并发腿2 用；null→占位 startConcurrent 走单腿等价）。 */
    private final SubPipelineRunner subPipelineRunner;
    /** 路由基线表（Task 14 并发腿2 deterministic 路由用）。 */
    private final RoutePlanBaselines baselines;
    /** 售后提交业务记忆（衔接判定：同 action 同订单/同会话未完结 → 回"已在处理中"，不重复澄清/提单）。 */
    private final WorkflowSubmissionRegistry submissions;
    /** 会话仲裁器（方案A·2026-09-18）：跨轮语义消歧升级层；LLM 缺席→恒走确定性分支（由 llm 派生，可空）。 */
    private final WorkflowTurnArbiter arbiter;
    /** 会话近期售后动作记忆（多动作绑定仲裁的触发依据；set 上限 4，map 超限粗清，demo 口径）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.LinkedHashSet<String>> recentActionsBySession =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_ACTIONS_PER_SESSION = 4;
    private static final int MAX_SESSIONS = 512;

    /** 测试便利构造（pendingStore=NO_OP，无装配器/LLM→硬编码话术，向后兼容既有 2 参调用方/测试）。 */
    public WorkflowExecutionStep(@Qualifier("refundAfterSaleWorkflow") AfterSaleWorkflow refundWorkflow,
                                 @Qualifier("returnAfterSaleWorkflow") AfterSaleWorkflow returnWorkflow) {
        this(refundWorkflow, returnWorkflow, PendingWorkflowStore.NO_OP, null, null, Runnable::run, null, new RoutePlanBaselines());
    }

    /** 测试便利构造（无装配器/LLM→硬编码话术）。 */
    public WorkflowExecutionStep(@Qualifier("refundAfterSaleWorkflow") AfterSaleWorkflow refundWorkflow,
                                 @Qualifier("returnAfterSaleWorkflow") AfterSaleWorkflow returnWorkflow,
                                 PendingWorkflowStore pendingStore) {
        this(refundWorkflow, returnWorkflow, pendingStore, null, null, Runnable::run, null, new RoutePlanBaselines());
    }

    /** 测试便利构造（澄清话术可注入装配器+LLM 桩；executor/subRunner/baselines 默认）。 */
    public WorkflowExecutionStep(@Qualifier("refundAfterSaleWorkflow") AfterSaleWorkflow refundWorkflow,
                                 @Qualifier("returnAfterSaleWorkflow") AfterSaleWorkflow returnWorkflow,
                                 PendingWorkflowStore pendingStore,
                                 SystemPromptAssembler promptAssembler, ChatLlmService llm) {
        this(refundWorkflow, returnWorkflow, pendingStore, promptAssembler, llm, Runnable::run, null, new RoutePlanBaselines());
    }

    @Autowired
    public WorkflowExecutionStep(@Qualifier("refundAfterSaleWorkflow") AfterSaleWorkflow refundWorkflow,
                                 @Qualifier("returnAfterSaleWorkflow") AfterSaleWorkflow returnWorkflow,
                                 PendingWorkflowStore pendingStore, SystemPromptAssembler promptAssembler,
                                 ChatLlmService llm, @Qualifier("workflowTaskExecutor") Executor executor,
                                 SubPipelineRunner subPipelineRunner, RoutePlanBaselines baselines) {
        this(refundWorkflow, returnWorkflow, pendingStore, promptAssembler, llm, executor,
                subPipelineRunner, baselines, new WorkflowSubmissionRegistry());
    }

    /** 全参构造（提交业务记忆可注入，测试可控）；{@code submissions} 为 null 时退化为无记忆判定。 */
    public WorkflowExecutionStep(@Qualifier("refundAfterSaleWorkflow") AfterSaleWorkflow refundWorkflow,
                                 @Qualifier("returnAfterSaleWorkflow") AfterSaleWorkflow returnWorkflow,
                                 PendingWorkflowStore pendingStore, SystemPromptAssembler promptAssembler,
                                 ChatLlmService llm, Executor executor,
                                 SubPipelineRunner subPipelineRunner, RoutePlanBaselines baselines,
                                 WorkflowSubmissionRegistry submissions) {
        this.refundWorkflow = refundWorkflow;
        this.returnWorkflow = returnWorkflow;
        this.pendingStore = (pendingStore != null) ? pendingStore : PendingWorkflowStore.NO_OP;
        this.promptAssembler = promptAssembler; // null→澄清话术直接硬编码兜底（②降级）
        this.llm = llm;                         // null→同上
        this.executor = (executor != null) ? executor : Runnable::run;
        this.subPipelineRunner = subPipelineRunner;
        this.baselines = (baselines != null) ? baselines : new RoutePlanBaselines();
        this.submissions = (submissions != null) ? submissions : new WorkflowSubmissionRegistry();
        this.arbiter = new WorkflowTurnArbiter(this.llm); // llm 可空 → 仲裁停用，恒走确定性分支
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        RoutePlan rp = context.routePlan();
        if (rp == null) {
            return new StepOutcome.Proceed(); // 防御
        }
        // 决策层接短期记忆：raw 原话优先，缺失回退 standardQuery（改写层结合会话历史补全的产物）
        String orderId = AfterSaleWorkflowGraph.extractOrderIdFrom(context);
        Optional<PendingWorkflow> pendingOpt = pendingStore.get(context.sessionId());
        boolean workflowIntent = "refund_request".equals(rp.intent()) || "return_request".equals(rp.intent());

        // 1. 衔接/仲裁判定（<b>先于 ambiguous 菜单</b>·T6"算了不退了"修复）：同动作活跃提交 →
        //    仲裁 WITHDRAW（撤销）/CLARIFY（澄清）/CONTINUE_ACTIVE（衔接话术，原行为）
        if (workflowIntent) {
            Optional<WorkflowSubmissionRegistry.Entry> active =
                    submissions.findActive(rp.intent(), orderId, context.sessionId());
            if (active.isPresent() && (orderId == null || active.get().orderId().equals(orderId))) {
                Optional<WorkflowTurnArbiter.Arbitration> arbitration = arbiter.arbitrate(
                        context, pendingOpt.map(PendingWorkflow::intent).orElse(null),
                        active.get(), recentActions(context.sessionId()));
                if (arbitration.isPresent() && arbitration.get().isWithdraw()) {
                    submissions.withdraw(active.get().action(), active.get().orderId(), context.sessionId());
                    pendingStore.remove(context.sessionId());
                    log.info("售后仲裁撤销：sessionId={} action={} orderId={}", // 审计
                            context.sessionId(), active.get().action(), active.get().orderId());
                    context.setPresetReply(withdrawMessage(active.get()));
                    return new StepOutcome.Proceed();
                }
                if (arbitration.isPresent() && arbitration.get().isClarify()) {
                    context.setPresetReply(clarifyMessage(context, rp.intent()));
                    pendingStore.put(context.sessionId(), new PendingWorkflow(rp.intent()));
                    recordRecentAction(context.sessionId(), rp.intent());
                    return new StepOutcome.Proceed();
                }
                // CONTINUE_ACTIVE / 仲裁缺席（LLM 未装配/失败/输出不合法）→ 现有衔接话术（确定性回退）
                log.info("售后衔接判定命中活跃提交，不重复澄清/提单：sessionId={} action={} orderId={} wfId={}", // 审计
                        context.sessionId(), active.get().action(), active.get().orderId(), active.get().workflowId());
                context.setPresetReply(continuationMessage(rp.intent(), active.get().workflowId()));
                pendingStore.remove(context.sessionId()); // 动作已受理，澄清 pending 不再需要
                return new StepOutcome.Proceed();
            }
        }
        // 2. ambiguous 菜单澄清（无活跃提交时到此）
        if (rp.ambiguous()) {
            if (workflowIntent) {
                recordRecentAction(context.sessionId(), rp.intent());
            }
            context.setPresetReply(clarifyAmbiguous(context, rp.intent(), orderId));
            return new StepOutcome.Proceed(); // pending 保留
        }
        // 2b. 无单号
        if (orderId == null) {
            if (workflowIntent) {
                if (rp.secondaryIntent() != null) {
                    return startConcurrent(context, rp.intent(), rp.secondaryIntent(), true); // 2b（Task 14 实现）
                }
                context.setPresetReply(clarifyMessage(context, rp.intent()));
                pendingStore.put(context.sessionId(), new PendingWorkflow(rp.intent())); // 覆盖=当前轮优先
                recordRecentAction(context.sessionId(), rp.intent());
                return new StepOutcome.Proceed();
            }
            return new StepOutcome.Proceed(); // 3. 无单号+非售后：pending 保留
        }
        // 2c. 多动作绑定仲裁（T3 型·用户裁决案例3）：订单号 + 会话近期 ≥2 个不同售后动作
        //     （"退货→退款→ORD-001"该退还是该退？）→ LLM 结合上下文绑定；CLARIFY → 澄清；
        //     仲裁缺席 → 原行为（跑本轮 routePlan intent）。
        if (workflowIntent && rp.secondaryIntent() == null && recentActions(context.sessionId()).size() >= 2) {
            Optional<WorkflowTurnArbiter.Arbitration> arbitration = arbiter.arbitrate(
                    context, pendingOpt.map(PendingWorkflow::intent).orElse(null),
                    null, recentActions(context.sessionId()));
            if (arbitration.isPresent() && arbitration.get().isBindRun()) {
                log.info("售后绑定仲裁：sessionId={} orderId={} bound={} reason={}", // 审计
                        context.sessionId(), orderId, arbitration.get().intent(), arbitration.get().reason());
                pendingStore.remove(context.sessionId());
                return runWorkflow(context, arbitration.get().intent(), orderId);
            }
            if (arbitration.isPresent() && arbitration.get().isClarify()) {
                context.setPresetReply(clarifyMessage(context, rp.intent()));
                pendingStore.put(context.sessionId(), new PendingWorkflow(rp.intent()));
                recordRecentAction(context.sessionId(), rp.intent());
                return new StepOutcome.Proceed();
            }
        }
        // 单号存在
        if (workflowIntent) {
            pendingStore.remove(context.sessionId());          // 先 remove（失败不残留）
            if (rp.secondaryIntent() != null) {
                return startConcurrent(context, rp.intent(), rp.secondaryIntent(), false); // 4b
            }
            return runWorkflow(context, rp.intent(), orderId); // 4. 单腿
        }
        if (ABANDON_SET.contains(rp.intent())) {
            pendingStore.remove(context.sessionId());          // 5/6. 当前轮优先
            return new StepOutcome.Proceed();
        }
        if (pendingOpt.isPresent()) {
            return startConcurrent(context, pendingOpt.get().intent(), rp.intent(), false); // 7
        }
        return new StepOutcome.Proceed(); // 8. 单号+非售后+无 pending
    }

    /** 会话近期售后动作（去重序；上限 4，超限淘汰最旧）。 */
    private List<String> recentActions(String sessionId) {
        java.util.LinkedHashSet<String> actions = recentActionsBySession.get(sessionId);
        return (actions == null) ? List.of() : List.copyOf(actions);
    }

    private void recordRecentAction(String sessionId, String intent) {
        if (sessionId == null || sessionId.isBlank()
                || (!"refund_request".equals(intent) && !"return_request".equals(intent))) {
            return;
        }
        if (recentActionsBySession.size() > MAX_SESSIONS) {
            recentActionsBySession.clear(); // demo 口径粗保护（真接入换 LRU/TTL）
        }
        java.util.LinkedHashSet<String> actions =
                recentActionsBySession.computeIfAbsent(sessionId, k -> new java.util.LinkedHashSet<>());
        actions.remove(intent); // 重排到末尾（最近优先）
        actions.add(intent);
        while (actions.size() > MAX_ACTIONS_PER_SESSION) {
            java.util.Iterator<String> it = actions.iterator();
            it.next();
            it.remove();
        }
    }

    /** 撤回话术（仲裁 WITHDRAW）：如实说明撤回语义（审批未完按撤回、已完成维持原结果）。 */
    private static String withdrawMessage(WorkflowSubmissionRegistry.Entry active) {
        String action = "return_request".equalsIgnoreCase(active.action()) ? "退货" : "退款";
        String workflowId = active.workflowId();
        String ref = (workflowId != null && !workflowId.isBlank()) ? "（售后单号 " + workflowId + "）" : "";
        return "已收到您的撤回请求" + ref + "。若审批尚未完成将按撤回处理，若已完成则维持原结果；如需再次办理请随时告知。";
    }

    private StepOutcome runWorkflow(PipelineContext context, String intent, String orderId) {
        AfterSaleWorkflow graph = "return_request".equals(intent) ? returnWorkflow : refundWorkflow;
        try {
            AfterSaleWorkflowOutcome outcome = graph.invoke(context);
            if (outcome instanceof AfterSaleWorkflowOutcome.Rejected r) {
                context.setPresetReply(r.customerMessage());
                return new StepOutcome.Proceed();
            }
            if (outcome instanceof AfterSaleWorkflowOutcome.Pending p) {
                // 提交制（2026-09-20）：建工单即返回——登记提交记忆（重复请求回"处理中"），如实告知"已提交人工审批"；
                // 同键此前已批准（alreadyApproved）不重复建单，回"此前已通过"话术
                registerSubmission(intent, orderId, context);
                context.setPresetReply(p.alreadyApproved()
                        ? approvedEarlierMessage(intent) : pendingApprovalMessage(intent));
                return new StepOutcome.Proceed();
            }
            return new StepOutcome.ShortCircuit(DegradationScenario.INTERNAL);
        } catch (Exception e) {
            log.warn("售后工作流执行失败，触发 INTERNAL 话术短路：sessionId={} reason={}", context.sessionId(), e.getMessage());
            return new StepOutcome.ShortCircuit(DegradationScenario.INTERNAL);
        }
    }

    /** 提交受理登记（Rejected/异常不登记：可修正后重试；提交制无请求内售后单号，workflowId=null）。 */
    private void registerSubmission(String intent, String orderId, PipelineContext context) {
        submissions.register(intent, orderId, null, context.sessionId());
    }

    // [[p0-intent-switch-clarify-design]] §7 并发 fork-join：腿1 工作流图 Future + 腿2 子管线 + 合并产物
    private StepOutcome startConcurrent(PipelineContext context, String workflowIntent, String qIntent, boolean clarifyLeg) {
        if (!clarifyLeg) {
            pendingStore.remove(context.sessionId());
        }
        // 腿2：子管线组装
        List<ChatMessage> leg2Prompt = null;
        if (subPipelineRunner != null) {
            PipelineContext sub = new PipelineContext(context.traceId(), context.sessionId(), context.rawInput());
            sub.setUserId(context.userId());
            sub.setHistory(context.history());
            sub.setStandardQuery(context.standardQuery());
            sub.setSummary(context.summary());
            sub.setIntent(context.intent());
            sub.setRoutePlan(RoutePlan.deterministic(baselines.baselineFor(qIntent)));
            leg2Prompt = subPipelineRunner.runCapabilitySegment(sub);
        }
        if (leg2Prompt != null && !leg2Prompt.isEmpty()) {
            leg2Prompt = new ArrayList<>(leg2Prompt); // 防不可变 List.of
            leg2Prompt.add(new ChatMessage.System(
                    "用户退款部分由系统另行回复，你只负责回答商品咨询部分，勿重复退款内容；若无检索结果请礼貌引导客户浏览其他商品，勿提及系统问题。"));
        }
        // 腿1：异步跑图 → 文本（Approved 确认/Rejected 驳回/Timeout+Denied+异常 优雅）
        Future<String> leg1Text = CompletableFuture.supplyAsync(
                () -> leg1TextFor(context, workflowIntent, clarifyLeg), executor);
        context.setConcurrentReply(new ConcurrentReply(leg1Text, leg2Prompt, qIntent));
        return new StepOutcome.Proceed();
    }

    /** 腿1 文本映射：clarifyLeg→澄清话术；否则克隆 context 跑图，按终态产文本。 */
    private String leg1TextFor(PipelineContext context, String workflowIntent, boolean clarifyLeg) {
        if (clarifyLeg) {
            return clarifyMessage(context, workflowIntent); // 2b：无单号，腿1 降级为澄清话术
        }
        PipelineContext clone = new PipelineContext(context.traceId(), context.sessionId(), context.rawInput());
        clone.setUserId(context.userId());
        // 改写层产物随行（与腿2 子上下文同口径）：订单号可能只存在于 standardQuery（短期记忆补全），
        // 缺失则图内 extractOrderId 回退链断在 raw → 误判 ORDER_NOT_FOUND（2026-09-20 review 修复）
        clone.setStandardQuery(context.standardQuery());
        String orderId = AfterSaleWorkflowGraph.extractOrderIdFrom(context); // 外层全量上下文（含记忆）
        try {
            AfterSaleWorkflow graph = "return_request".equals(workflowIntent) ? returnWorkflow : refundWorkflow;
            AfterSaleWorkflowOutcome o = graph.invoke(clone);
            if (o instanceof AfterSaleWorkflowOutcome.Rejected r) {
                return r.customerMessage();
            }
            if (o instanceof AfterSaleWorkflowOutcome.Pending p) {
                submissions.register(workflowIntent, orderId, null, context.sessionId());
                return p.alreadyApproved() ? approvedEarlierMessage(workflowIntent)
                        : pendingApprovalMessage(workflowIntent);
            }
            return WORKFLOW_PENDING_TEXT; // 异常兜底（图终态已收缩为两态，此处理论不可达）
        } catch (Exception e) {
            log.warn("并发腿1 工作流失败，优雅话术：sessionId={} reason={}", context.sessionId(), e.getMessage());
            return WORKFLOW_PENDING_TEXT;
        }
    }

    /**
     * entity-gate 澄清话术（用户钦定"没提供订单号应先澄清"）。
     *
     * <p><b>生成方式定案（2026-09-17 用户裁决）</b>：澄清话术不落静态模板、不做硬编码主路径——
     * 面向模型的指令（{@code system-prompt-segments} 片段按意图装配 + 澄清任务指令）交大模型生成
     * 客户话术；硬编码文本仅作 ②降级兜底（LLM 不可用/空回复/本步未装配 LLM）。
     */
    private String clarifyMessage(PipelineContext context, String intent) {
        String generated = generateClarify(context, intent, clarifyTaskFor(intent));
        return (generated != null) ? generated : hardcodedClarify(intent);
    }

    /** 澄清任务指令（面向模型）：情况 + 输出约束；固定事实锚点（订单号格式示例 ORD-001）进指令防编造。 */
    private static String clarifyTaskFor(String intent) {
        String action = "return_request".equals(intent) ? "退货" : "退款";
        return "当前任务：生成一条发给客户的澄清话术。\n"
                + "情况：用户希望办理" + action + "，但尚未提供订单号。\n"
                + "要求：\n"
                + "1. 礼貌请用户提供订单号，并给出订单号格式示例（例如 ORD-001）；\n"
                + "2. 说明收到订单号后你将核实订单状态与" + action + "资格；\n"
                + "3. 只输出这条话术本身，不要任何解释、前缀或 Markdown；不得编造订单状态或政策结论。";
    }

    /**
     * 澄清话术生成主路径：装配器取面向模型的指令（{@code system-prompt-segments} 片段按
     * coarse={@link PipelineContext#intent()} / fine=routePlan.intent() 选段，与
     * {@link com.agentdemo007.context.SystemAnchorLayer} 同一套装配），拼接任务指令后经
     * {@link ChatLlmService#chatRaw}（不二次包裹，指令即指令）出站，scene=澄清话术。
     *
     * @return 生成的客户话术；本步未装配装配器/LLM（dev 桩、测试便利构造）、LLM 失败或空回复 → null（调方落硬编码兜底）
     */
    private String generateClarify(PipelineContext context, String fineIntent, String task) {
        if (promptAssembler == null || llm == null) {
            return null;
        }
        try {
            String system = promptAssembler.assemble(context.intent(), fineIntent, Map.of());
            String prompt = system + "\n\n" + task;
            // 提交LLM前的指令检查（同 OutputStep「prompt 检查」风格）：澄清指令=片段装配+任务指令，dev 可见。
            if (log.isDebugEnabled()) {
                log.debug("提交LLM的澄清话术指令（面向模型，片段装配+任务指令）：sessionId={} intent={}\n{}",
                        context.sessionId(), fineIntent, prompt);
            }
            String reply = llm.chatRaw(prompt,
                    (context.intent() != null) ? context.intent() : Intent.OTHER, "澄清话术");
            if (reply != null && !reply.isBlank()) {
                log.info("澄清话术已由大模型生成：sessionId={} intent={} scene=澄清话术", context.sessionId(), fineIntent);
                return reply.trim();
            }
            log.warn("澄清话术 LLM 空回复，回退硬编码兜底（②降级）：sessionId={} intent={}", context.sessionId(), fineIntent);
            return null;
        } catch (Exception e) {
            log.warn("澄清话术 LLM 生成失败，回退硬编码兜底（②降级）：sessionId={} intent={} reason={}",
                    context.sessionId(), fineIntent, e.getMessage());
            return null;
        }
    }

    /** ②降级兜底话术（LLM 未装配/失败时用，行为与历史一致）。 */
    private static String hardcodedClarify(String intent) {
        if ("return_request".equals(intent)) {
            return "好的，为您办理退货。请提供您的订单号（例如 ORD-001），我将为您核实订单状态与退货资格。";
        }
        if ("refund_request".equals(intent)) {
            return "好的，为您办理退款。请提供您的订单号（例如 ORD-001），我将为您核实订单状态与退款资格。";
        }
        return "请提供您的订单号（例如 ORD-001），以便为您办理售后服务。";
    }

    /**
     * ambiguous 澄清话术（[[p0-intent-switch-clarify-design]] §6·菜单式澄清）。同澄清定案：
     * 大模型按片段指令生成（固定菜单选项写死在任务指令里防漂移），失败/空→硬编码菜单兜底；
     * 订单号提示仅无单号时进指令与兜底。
     */
    private String clarifyAmbiguous(PipelineContext context, String intent, String orderId) {
        String hint = hintFor(intent);
        StringBuilder task = new StringBuilder()
                .append("当前任务：生成一条发给客户的澄清话术。\n")
                .append("情况：用户的需求意图不明确").append(hint).append("，需要澄清。\n")
                .append("要求：\n")
                .append("1. 礼貌请用户明确当前需求，选项固定为：退款 / 退货 / 查订单 / 商品咨询；\n");
        if (orderId == null) {
            task.append("2. 提示如涉及退款/退货，请一并提供订单号；\n");
        }
        task.append("3. 只输出这条话术本身，不要任何解释、前缀或 Markdown；不得编造信息。");
        String generated = generateClarify(context, intent, task.toString());
        if (generated != null) {
            return generated;
        }
        String orderHint = (orderId == null) ? "（如涉及退款/退货，请一并提供订单号）" : "";
        return "您的需求涉及多个方面" + hint + "，请明确您当前需要：退款 / 退货 / 查订单 / 商品咨询？" + orderHint;
    }

    private static String hintFor(String intent) {
        if ("refund_request".equals(intent)) return "（可能涉及退款）";
        if ("return_request".equals(intent)) return "（可能涉及退货）";
        return "";
    }

    /**
     * Pending 提交话术（提交制 2026-09-20）：建工单即返回——如实告知"已提交人工审批"；
     * 批准/驳回是管理台的工单状态事件，后续经工单查询工具获知进度。
     */
    private static String pendingApprovalMessage(String intent) {
        String action = "return_request".equals(intent) ? "退货" : "退款";
        return "您的" + action + "申请已提交人工审批，审批结果将另行通知，请耐心等候。";
    }

    /** 同键此前已批准话术（alreadyApproved：不重复建单防重复业务动作，如实告知已受理）。 */
    private static String approvedEarlierMessage(String intent) {
        String action = "return_request".equals(intent) ? "退货" : "退款";
        return "您的" + action + "申请此前已通过人工审批，业务办理中，请耐心等候。";
    }

    /** 衔接话术（同 action 活跃提交命中）：不重复提单/澄清，如实告知处理中 + 售后单号。 */
    private static String continuationMessage(String intent, String workflowId) {
        String action = "return_request".equals(intent) ? "退货" : "退款";
        String ref = (workflowId != null && !workflowId.isBlank()) ? "（售后单号 " + workflowId + "）" : "";
        return "您的" + action + "申请已在处理中" + ref + "，无需重复提交；如需查询进度请提供订单号，审批结果请留意后续通知。";
    }
}
