package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.capability.plan.RoutePlanBaselines;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.prompt.PromptRegistry;
import com.agentdemo007.prompt.VersionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 高风险固定工作流触发步（第四层·{@code @Order(670)}，紧随 {@code RagStep(660)}、先于 {@code ContextBuilder(700)}）。
 *
 * <p><b>@670 状态机</b>（[[p0-intent-switch-clarify-design]] §5）：按当前轮 {@link RoutePlan} 的
 * intent/ambiguous/secondaryIntent + rawInput 是否含订单号 + pending 状态，十分支决策续跑/切换/放弃/并发。
 * ambiguous 最先短路（澄清菜单，pending 保留）；无单号 workflow→澄清存 pending；单号+workflow→先 remove 再跑图；
 * 单号+abandon 集（order_query/security_request/...）→先 remove 主链；单号+非售后+pending→并发（Task 14）。
 *
 * <p><b>entity-gate 澄清</b>（用户钦定"没提供订单号应先澄清，不直接跑工作流/返政策"）：requiresWorkflow=true 但
 * rawInput 无订单号 → {@code context.setPresetReply(澄清话术)} + {@code pendingStore.put(sessionId, PendingWorkflow(intent))}
 * + Proceed（图不调）；话术经 {@link com.agentdemo007.output.OutputStep} presetReply 守卫短路跳 LLM/政策文本。
 * Turn 2 由本步状态机按当前轮 routePlan 决策续跑/切换/放弃。
 *
 * <p><b>return/refund 流选择</b>（D1 参数化图）：{@code "return_request".equals(rp.intent())} → 退货图
 * （{@link PolicyDomain#RETURN} + {@link ReturnValidationRule}），否则（refund_request）→ 退款图
 * （{@link PolicyDomain#REFUND} + {@link RefundValidationRule}）。两图均 {@link AfterSaleWorkflow} seam，
 * 由 {@link WorkflowConfig} 按属性门控装配（NO_OP seams 跑通图结构，真后端迭代后补）。
 *
 * <p>属性门控 {@code app.workflow.enabled}（缺省 false）→ 本 bean 不装配，@670 槽空，
 * 旧链 {@code RagStep(660)→ContextBuilder(700)} 线性等价不变；enabled=true 时装配。
 *
 * <p><b>终态收口（④统一收口，[[business-tools-workflow-dag]] §2.4·E1）</b>——图返 {@link AfterSaleWorkflowOutcome}，
 * 本步按终态映射（图<b>不直接产 {@code PipelineResult}</b>，写 context，终态由顶层 {@code PipelineExecutor} 收口）：
 * <ul>
 *   <li>{@link AfterSaleWorkflowOutcome.Approved} → {@code context.setPresetReply(确认话术)} + {@link StepOutcome.Proceed}（完成）；</li>
 *   <li>{@link AfterSaleWorkflowOutcome.Rejected} → {@code context.setPresetReply(message)} + {@link StepOutcome.Proceed}
 *       （<b>业务驳回≠系统失败</b>，E1：不 ShortCircuit、不混 {@link DegradationScenario}；话术走 presetReply 短路）；</li>
 *   <li>{@link AfterSaleWorkflowOutcome.Timeout} → {@link StepOutcome.ShortCircuit}({@link DegradationScenario#WORKFLOW_APPROVAL_TIMEOUT})
 *       （退款已提交但未获批准，不假装成功）；</li>
 *   <li>{@link AfterSaleWorkflowOutcome.Denied}（maxIterations 强制终止）→ {@link StepOutcome.ShortCircuit}({@link DegradationScenario#INTERNAL})；</li>
 *   <li>子图异常 → {@link StepOutcome.ShortCircuit}({@link DegradationScenario#INTERNAL})。</li>
 * </ul>
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

    /** 退款流图（refund_request → PolicyDomain.REFUND + RefundValidationRule）。 */
    private final AfterSaleWorkflow refundWorkflow;
    /** 退货流图（return_request → PolicyDomain.RETURN + ReturnValidationRule）。 */
    private final AfterSaleWorkflow returnWorkflow;
    /** 待续跑工作流存储（[[business-tools-workflow-dag]] §2.5·Turn 1 澄清存 intent，Turn 2 续跑）。 */
    private final PendingWorkflowStore pendingStore;
    /** 提示词注册中心（[[q1-nacos-prompt-mgmt]]：澄清话术 registry 化，null→硬编码兜底）。 */
    private final PromptRegistry promptRegistry;
    /** 并发执行器（Task 14 真并发用；单腿/占位阶段=Runnable::run 同步）。 */
    private final Executor executor;
    /** 子管线 runner（Task 14 并发腿2 用；null→占位 startConcurrent 走单腿等价）。 */
    private final SubPipelineRunner subPipelineRunner;
    /** 路由基线表（Task 14 并发腿2 deterministic 路由用）。 */
    private final RoutePlanBaselines baselines;

    /** 测试便利构造（pendingStore=NO_OP，registry=null→硬编码话术，向后兼容既有 2 参调用方/测试）。 */
    public WorkflowExecutionStep(@Qualifier("refundAfterSaleWorkflow") AfterSaleWorkflow refundWorkflow,
                                 @Qualifier("returnAfterSaleWorkflow") AfterSaleWorkflow returnWorkflow) {
        this(refundWorkflow, returnWorkflow, PendingWorkflowStore.NO_OP, null, Runnable::run, null, new RoutePlanBaselines());
    }

    /** 测试便利构造（registry=null→硬编码话术）。 */
    public WorkflowExecutionStep(@Qualifier("refundAfterSaleWorkflow") AfterSaleWorkflow refundWorkflow,
                                 @Qualifier("returnAfterSaleWorkflow") AfterSaleWorkflow returnWorkflow,
                                 PendingWorkflowStore pendingStore) {
        this(refundWorkflow, returnWorkflow, pendingStore, null, Runnable::run, null, new RoutePlanBaselines());
    }

    /** 测试便利构造（executor/subRunner/baselines 默认）。 */
    public WorkflowExecutionStep(@Qualifier("refundAfterSaleWorkflow") AfterSaleWorkflow refundWorkflow,
                                 @Qualifier("returnAfterSaleWorkflow") AfterSaleWorkflow returnWorkflow,
                                 PendingWorkflowStore pendingStore,
                                 PromptRegistry promptRegistry) {
        this(refundWorkflow, returnWorkflow, pendingStore, promptRegistry, Runnable::run, null, new RoutePlanBaselines());
    }

    @Autowired
    public WorkflowExecutionStep(@Qualifier("refundAfterSaleWorkflow") AfterSaleWorkflow refundWorkflow,
                                 @Qualifier("returnAfterSaleWorkflow") AfterSaleWorkflow returnWorkflow,
                                 PendingWorkflowStore pendingStore, PromptRegistry promptRegistry,
                                 Executor executor, SubPipelineRunner subPipelineRunner,
                                 RoutePlanBaselines baselines) {
        this.refundWorkflow = refundWorkflow;
        this.returnWorkflow = returnWorkflow;
        this.pendingStore = (pendingStore != null) ? pendingStore : PendingWorkflowStore.NO_OP;
        this.promptRegistry = promptRegistry; // null→clarifyMessage 回退硬编码（②降级）
        this.executor = (executor != null) ? executor : Runnable::run;
        this.subPipelineRunner = subPipelineRunner;
        this.baselines = (baselines != null) ? baselines : new RoutePlanBaselines();
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        RoutePlan rp = context.routePlan();
        if (rp == null) {
            return new StepOutcome.Proceed(); // 防御
        }
        String orderId = AfterSaleWorkflowGraph.extractOrderIdFrom(context.rawInput());
        Optional<PendingWorkflow> pendingOpt = pendingStore.get(context.sessionId());
        boolean workflowIntent = "refund_request".equals(rp.intent()) || "return_request".equals(rp.intent());

        // 1. ambiguous 最先短路
        if (rp.ambiguous()) {
            context.setPresetReply(clarifyAmbiguous(rp.intent(), orderId));
            return new StepOutcome.Proceed(); // pending 保留
        }
        // 2/2b. 无单号
        if (orderId == null) {
            if (workflowIntent) {
                if (rp.secondaryIntent() != null) {
                    return startConcurrent(context, rp.intent(), rp.secondaryIntent(), true); // 2b（Task 14 实现）
                }
                context.setPresetReply(clarifyMessage(rp.intent()));
                pendingStore.put(context.sessionId(), new PendingWorkflow(rp.intent())); // 覆盖=当前轮优先
                return new StepOutcome.Proceed();
            }
            return new StepOutcome.Proceed(); // 3. 无单号+非售后：pending 保留
        }
        // 单号存在
        if (workflowIntent) {
            pendingStore.remove(context.sessionId());          // 先 remove（失败不残留）
            if (rp.secondaryIntent() != null) {
                return startConcurrent(context, rp.intent(), rp.secondaryIntent(), false); // 4b
            }
            return runWorkflow(context, rp.intent());          // 4. 单腿
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

    private StepOutcome runWorkflow(PipelineContext context, String intent) {
        AfterSaleWorkflow graph = "return_request".equals(intent) ? returnWorkflow : refundWorkflow;
        try {
            AfterSaleWorkflowOutcome outcome = graph.invoke(context);
            if (outcome instanceof AfterSaleWorkflowOutcome.Rejected r) {
                context.setPresetReply(r.customerMessage());
                return new StepOutcome.Proceed();
            }
            if (outcome instanceof AfterSaleWorkflowOutcome.Approved a) {
                context.setPresetReply(confirmationMessage(intent, context.workflowResult(), a.orderStatus(), a.policyConclusion()));
                return new StepOutcome.Proceed();
            }
            if (outcome instanceof AfterSaleWorkflowOutcome.Timeout) {
                return new StepOutcome.ShortCircuit(DegradationScenario.WORKFLOW_APPROVAL_TIMEOUT);
            }
            if (outcome instanceof AfterSaleWorkflowOutcome.Denied) {
                return new StepOutcome.ShortCircuit(DegradationScenario.INTERNAL);
            }
            return new StepOutcome.ShortCircuit(DegradationScenario.INTERNAL);
        } catch (Exception e) {
            log.warn("售后工作流执行失败，触发 INTERNAL 话术短路：sessionId={} reason={}", context.sessionId(), e.getMessage());
            return new StepOutcome.ShortCircuit(DegradationScenario.INTERNAL);
        }
    }

    // Task 14 实现；本任务先留占位（单腿等价：跑图）以免并发触发分支编译不过
    private StepOutcome startConcurrent(PipelineContext context, String workflowIntent, String qIntent, boolean clarifyLeg) {
        return runWorkflow(context, workflowIntent);
    }

    /**
     * entity-gate 澄清话术（用户钦定"没提供订单号应先澄清"）。意图感知——退货/退款分别引导，
     * 均要求提供订单号。经 {@link com.agentdemo007.output.OutputStep} presetReply 守卫 → securityFilter 脱敏 → finalReply 短路。
     */
    private String clarifyMessage(String intent) {
        // [[q1-nacos-prompt-mgmt]] 澄清话术走 PromptRegistry（key=clarify-return/clarify-refund）：
        // registry 命中→用模板（Nacos 热改不 redeploy）；miss/空/registry null→回退硬编码（②降级，行为不变）。
        String key = clarifyPromptKey(intent);
        if (promptRegistry != null && key != null) {
            String rendered = promptRegistry.get(key, VersionSpec.latest())
                    .map(t -> t.render(Map.of()))
                    .filter(s -> s != null && !s.isBlank())
                    .orElse(null);
            if (rendered != null) {
                return rendered;
            }
        }
        return hardcodedClarify(intent);
    }

    /** 澄清话术 registry key（return/refund；其余 intent 无专属 key→generic 硬编码）。 */
    private static String clarifyPromptKey(String intent) {
        if ("return_request".equals(intent)) return "clarify-return";
        if ("refund_request".equals(intent)) return "clarify-refund";
        return null;
    }

    /** registry 缺失时的内置话术（②降级兜底，与既有行为一致）。 */
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
     * ambiguous 澄清话术（[[p0-intent-switch-clarify-design]] §6·菜单式澄清）。意图感知提示 +
     * 订单号提示（无单号时要求提供）。经 PromptRegistry（key=clarify-ambiguous）热改；miss/空→硬编码兜底。
     */
    private String clarifyAmbiguous(String intent, String orderId) {
        String hint = hintFor(intent);
        String orderHint = (orderId == null) ? "（如涉及退款/退货，请一并提供订单号）" : "";
        String key = "clarify-ambiguous";
        if (promptRegistry != null) {
            String rendered = promptRegistry.get(key, VersionSpec.latest())
                    .map(t -> t.render(Map.of("intent_hint", hint, "order_hint", orderHint)))
                    .filter(s -> s != null && !s.isBlank())
                    .orElse(null);
            if (rendered != null) {
                return rendered;
            }
        }
        return "您的需求涉及多个方面" + hint + "，请明确您当前需要：退款 / 退货 / 查订单 / 商品咨询？" + orderHint;
    }

    private static String hintFor(String intent) {
        if ("refund_request".equals(intent)) return "（可能涉及退款）";
        if ("return_request".equals(intent)) return "（可能涉及退货）";
        return "";
    }

    /**
     * Approved 确认话术（E1 收口·话术短路铁律）。意图感知——退货/退款分别确认，含订单状态/政策结论/售后单号
     * （submit 节点写 ctx.workflowResult）。经 OutputStep presetReply 守卫→securityFilter 脱敏→finalReply 短路。
     *
     * <p>TODO [[q1-nacos-prompt-mgmt]]：确认话术 registry 化（key=approve-return/approve-refund）。当前硬编码。
     */
    private static String confirmationMessage(String intent, String workflowResult, String orderStatus, String policyConclusion) {
        String action = "return_request".equals(intent) ? "退货" : "退款";
        String statusPart = (orderStatus != null || policyConclusion != null)
                ? "订单当前" + nullToEmpty(orderStatus) + "，" + nullToEmpty(policyConclusion) + "，"
                : "";
        String ref = (workflowResult != null && !workflowResult.isBlank()) ? "售后单号 " + workflowResult + "，" : "";
        return "您的" + action + "申请已受理，" + statusPart + ref + "已提交审批，请耐心等候。";
    }

    private static String nullToEmpty(String s) { return s != null ? s : ""; }
}
