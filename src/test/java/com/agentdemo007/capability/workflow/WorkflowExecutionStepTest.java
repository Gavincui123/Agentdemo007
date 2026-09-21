package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.kb.KbLevel;
import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.capability.plan.RoutePlanBaselines;
import com.agentdemo007.capability.plan.RoutePlanCandidate;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.context.SystemAnchorLayer;
import com.agentdemo007.context.SystemPromptAssembler;
import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.prompt.LocalPromptSource;
import com.agentdemo007.prompt.PromptTemplate;
import com.agentdemo007.session.model.ChatMessage;
import com.agentdemo007.session.model.StandardQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkflowExecutionStep} 单测（高风险固定工作流·[[business-tools-workflow-dag]] §2.4 收口·
 * 2026-09-20 提交制收缩）。
 *
 * <p>触发步 {@code @Order(670)} 守卫（#135 source 门控 + requiresWorkflow）不变；本片验<b>终态收口</b>：
 * {@link AfterSaleWorkflowGraph} 返 {@link AfterSaleWorkflowOutcome}（sealed 两态），本步按终态映射——
 * <ul>
 *   <li>{@link AfterSaleWorkflowOutcome.Rejected} → {@code context.setPresetReply(message)} + Proceed
 *       （业务驳回≠系统失败，E1：不 ShortCircuit，话术走 presetReply 短路，{@link com.agentdemo007.output.OutputStep} 跳 LLM）；</li>
 *   <li>{@link AfterSaleWorkflowOutcome.Pending} → 登记提交记忆（重复请求回"处理中"）+
 *       {@code presetReply(已提交人工审批话术)} + Proceed（提交制：建工单即返回，无请求内等待；
 *       alreadyApproved=true 回"此前已通过"话术，不重复建单）。</li>
 * </ul>
 *
 * <p>return/refund 流选择（D1 参数化图）：{@code "return_request".equals(rp.intent())} → 退货图，否则 → 退款图。
 * 图用 {@link AfterSaleWorkflow} seam（scripted lambda 返固定终态），聚焦收口逻辑——图内部 DAG 由
 * {@link AfterSaleWorkflowGraphTest} 覆盖；本测不重跑 5 节点图。
 * 属性门控（{@code app.workflow.enabled}）/真 seams 在 {@link WorkflowConfig}。
 */
class WorkflowExecutionStepTest {

    /** 高风险候选（requires_workflow=true，WORKFLOW_FIRST，HIGH），intent 可指定（refund/return）。 */
    private static RoutePlan llmWorkflowPlan(String intent) {
        RoutePlanCandidate c = new RoutePlanCandidate(
                intent, false, false,
                List.of("get_order_detail"), List.of("after_sale_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true,
                RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST);
        return new RoutePlan(c, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, List.of());
    }

    /** 低风险 FAQ 候选（requires_workflow=false）。 */
    private static RoutePlan llmNoWorkflowPlan() {
        RoutePlanCandidate c = new RoutePlanCandidate(
                "faq", true, false,
                List.of(), List.of("faq"),
                RoutePlanCandidate.RiskLevel.LOW, false,
                RoutePlanCandidate.FallbackPolicy.SAFE_DETERMINISTIC_PATH);
        return new RoutePlan(c, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, List.of());
    }

    /** 确定性兜底候选（requires_workflow=true，source=FALLBACK；#135 放宽后亦触发·信任护栏收敛）。 */
    private static RoutePlan fallbackWorkflowPlan() {
        RoutePlanCandidate c = new RoutePlanCandidate(
                "refund_request", false, false,
                List.of("get_order_detail"), List.of("after_sale_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true,
                RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST);
        return RoutePlan.deterministic(c);
    }

    /** T9 状态机用工厂：intent + workflow 标志（8 参兼容构造，ambiguous=false/secondaryIntent=null）。 */
    private static RoutePlan llmPlan(String intent, boolean workflow) {
        RoutePlanCandidate c = new RoutePlanCandidate(
                intent, workflow, false,
                workflow ? List.of("get_order_detail") : List.of(),
                workflow ? List.of("after_sale_policy") : List.of(),
                workflow ? RoutePlanCandidate.RiskLevel.HIGH : RoutePlanCandidate.RiskLevel.LOW,
                workflow, RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST);
        return new RoutePlan(c, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, List.of());
    }

    /** 计数 canary：记 invoke 次数 + 返固定终态（验"是否触发"；提交制终态=Pending）。 */
    private static final class InvokeCanary {
        final AtomicInteger count = new AtomicInteger();
        private final AfterSaleWorkflowOutcome outcome;
        InvokeCanary(AfterSaleWorkflowOutcome outcome) { this.outcome = outcome; }
        AfterSaleWorkflow graph() {
            return ctx -> { count.incrementAndGet(); return outcome; };
        }
    }

    private static WorkflowExecutionStep step(InvokeCanary refund, InvokeCanary ret) {
        return new WorkflowExecutionStep(refund.graph(), ret.graph());
    }

    private static WorkflowExecutionStep stepWithRefund(AfterSaleWorkflowOutcome outcome) {
        return step(new InvokeCanary(outcome), new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)));
    }

    // ---- 守卫：不触发工作流 → Proceed，图未调 ----

    @Test
    void process_routePlanNull_skipsWorkflow_proceeds() {
        InvokeCanary refund = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        InvokeCanary ret = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        WorkflowExecutionStep s = step(refund, ret);

        StepOutcome outcome = s.process(new PipelineContext("s1", "退款 ORD-001"));

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(refund.count.get()).isZero();
        assertThat(ret.count.get()).isZero();
    }

    @Test
    void process_deterministicFallback_requiresWorkflow_triggersWorkflow() {
        // #135 放宽（用户钦定·信任护栏收敛）：source=DETERMINISTIC_FALLBACK 但 requiresWorkflow=true
        // （converge 兜底恒把 refund/return 置 true）→ 触发工作流（不再因 source 跳过）
        InvokeCanary refund = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        InvokeCanary ret = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        WorkflowExecutionStep s = step(refund, ret);

        PipelineContext context = new PipelineContext("s1", "退款 ORD-001");
        context.setRoutePlan(fallbackWorkflowPlan()); // source=FALLBACK + requiresWorkflow=true → 现在触发
        StepOutcome outcome = s.process(context);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(refund.count.get()).isEqualTo(1); // 放宽后 DET 也触发退款图
        assertThat(ret.count.get()).isZero();
    }

    @Test
    void process_llmSource_requiresWorkflowFalse_skipsWorkflow_proceeds() {
        InvokeCanary refund = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        InvokeCanary ret = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        WorkflowExecutionStep s = step(refund, ret);

        PipelineContext context = new PipelineContext("s1", "退款政策是什么");
        context.setRoutePlan(llmNoWorkflowPlan()); // requiresWorkflow=false
        StepOutcome outcome = s.process(context);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(refund.count.get()).isZero();
    }

    // ---- entity-gate 澄清（用户钦定"没提供订单号应先澄清，不直接跑工作流/返政策"）----

    @Test
    void process_requiresWorkflowNoOrderId_clarifiesAndStoresPending_skipsGraph() {
        // [[business-tools-workflow-dag]] 用户钦定：requiresWorkflow=true 但 rawInput 无 ORD-\d+ 订单号 →
        // setPresetReply(澄清话术) + 存 pending{intent}（供 @670 状态机 Turn 2 续跑）+ Proceed，
        // 图不调（InvokeCanary=0）。话术经 OutputStep presetReply 守卫短路（跳 LLM/政策文本）。
        InvokeCanary refund = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        InvokeCanary ret = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        InMemoryPendingWorkflowStore store = new InMemoryPendingWorkflowStore();
        WorkflowExecutionStep s = new WorkflowExecutionStep(refund.graph(), ret.graph(), store);

        PipelineContext context = new PipelineContext("s1", "我要退货"); // 无订单号
        context.setRoutePlan(llmWorkflowPlan("return_request"));
        StepOutcome outcome = s.process(context);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(refund.count.get()).isZero(); // 图未调（澄清前置，不进 query_order→ORDER_NOT_FOUND）
        assertThat(ret.count.get()).isZero();
        assertThat(context.presetReply()).isNotNull(); // 澄清话术（非政策文本）
        assertThat(context.presetReply()).contains("订单号");
        assertThat(store.get("s1")).isPresent(); // pending 已存（续跑用）
        assertThat(store.get("s1").orElseThrow().intent()).isEqualTo("return_request");
    }

    // ---- 澄清话术生成定案（2026-09-17 用户裁决）：面向模型的指令（system-prompt-segments 片段装配 +
    // 澄清任务指令）→ 大模型生成客户话术；LLM 失败/空/未装配 → 硬编码 ②降级兜底。不设静态模板 key。

    /** 带全局片段的装配器：验证 system-prompt-segments 片段作为指令前缀进入澄清 prompt（与回答生成同一装配通路）。 */
    private static SystemPromptAssembler assemblerWithGlobalSegment() {
        LocalPromptSource registry = new LocalPromptSource();
        registry.put("system-prompt-segments", new PromptTemplate("system-prompt-segments", "1.0",
                "- id: persona\n"
                + "  scene: all\n"
                + "  sort: 100\n"
                + "  enabled: true\n"
                + "  prompt: 全局人设与政策指令（测试片段桩）", "md5-test"));
        return new SystemPromptAssembler(registry, SystemAnchorLayer.DEFAULT_SYSTEM_PROMPT);
    }

    @Test
    void process_clarify_generatedByLlm_withModelFacingInstructions() {
        ChatLlmService llm = Mockito.mock(ChatLlmService.class);
        Mockito.when(llm.chatRaw(Mockito.anyString(), Mockito.any(), Mockito.eq("澄清话术")))
                .thenReturn("麻烦您提供一下订单号（如 ORD-001），我来为您核实退款资格。");
        InMemoryPendingWorkflowStore store = new InMemoryPendingWorkflowStore();
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph(),
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph(),
                store, assemblerWithGlobalSegment(), llm);

        PipelineContext context = new PipelineContext("s1", "我要退款"); // 无订单号
        context.setRoutePlan(llmWorkflowPlan("refund_request"));
        s.process(context);

        assertThat(context.presetReply())
                .isEqualTo("麻烦您提供一下订单号（如 ORD-001），我来为您核实退款资格。"); // LLM 生成直用（非模板/硬编码）
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(llm).chatRaw(prompt.capture(), Mockito.any(), Mockito.eq("澄清话术")); // scene 可辨
        assertThat(prompt.getValue())
                .startsWith("全局人设与政策指令（测试片段桩）") // 片段=面向模型的指令前缀
                .contains("澄清")                             // 任务指令在后
                .contains("ORD-001");                         // 事实锚点（防编造单号格式）
        assertThat(store.get("s1")).isPresent(); // pending 照存（Turn 2 续跑用）
    }

    @Test
    void process_clarify_fallsBackToHardcoded_whenLlmFails() {
        ChatLlmService llm = Mockito.mock(ChatLlmService.class);
        Mockito.when(llm.chatRaw(Mockito.anyString(), Mockito.any(), Mockito.eq("澄清话术")))
                .thenThrow(new RuntimeException("模型不可用"));
        InMemoryPendingWorkflowStore store = new InMemoryPendingWorkflowStore();
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph(),
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph(),
                store, assemblerWithGlobalSegment(), llm);

        PipelineContext context = new PipelineContext("s1", "我要退货");
        context.setRoutePlan(llmWorkflowPlan("return_request"));
        s.process(context);

        assertThat(context.presetReply()).contains("订单号"); // 硬编码兜底（②降级）
        assertThat(store.get("s1")).isPresent();
    }

    // ---- 收口：按 AfterSaleWorkflowOutcome 终态映射（提交制两态）----

    @Test
    void process_pendingSubmitted_proceedsAndInvokesRefund() {
        InvokeCanary refund = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        InvokeCanary ret = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(true));
        WorkflowExecutionStep s = step(refund, ret);

        PipelineContext context = new PipelineContext("s1", "退款 ORD-001");
        context.setRoutePlan(llmWorkflowPlan("refund_request"));
        StepOutcome outcome = s.process(context);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(refund.count.get()).isEqualTo(1); // 触发退款图一次
        assertThat(ret.count.get()).isZero(); // 退款流不调退货图
        // E1 收口：Pending→presetReply 提交话术短路（跳 LLM），OutputStep 守卫见非空即跳
        assertThat(context.presetReply()).contains("退款").contains("已提交人工审批");
        assertThat(context.degraded()).isFalse(); // 提交≠降级
    }

    @Test
    void process_pending_alreadyApproved_setsEarlierReply_noResubmit() {
        // 提交制幂等（2026-09-20）：同键工单此前已批准（alreadyApproved=true）→ 不重复建单，
        // 回"此前已通过"话术（如实、不回"已提交"）
        WorkflowExecutionStep s = stepWithRefund(new AfterSaleWorkflowOutcome.Pending(true));

        PipelineContext context = new PipelineContext("s1", "退款 ORD-001");
        context.setRoutePlan(llmWorkflowPlan("refund_request"));
        StepOutcome outcome = s.process(context);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(context.presetReply()).contains("退款").contains("已通过人工审批");
        assertThat(context.presetReply()).doesNotContain("已提交人工审批");
        assertThat(context.degraded()).isFalse();
    }

    @Test
    void process_rejected_setsPresetReplyAndProceeds() {
        WorkflowExecutionStep s = stepWithRefund(
                new AfterSaleWorkflowOutcome.Rejected(Reason.ORDER_NOT_OWNED, "订单 ORD-003 不属于当前账户，无法代为办理退货/退款。"));

        PipelineContext context = new PipelineContext("s1", "退货 ORD-003");
        context.setRoutePlan(llmWorkflowPlan("refund_request"));
        StepOutcome outcome = s.process(context);

        // 业务驳回≠系统失败（E1）：不 ShortCircuit，写 presetReply + Proceed → OutputStep 跳 LLM 话术短路
        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(context.presetReply()).isEqualTo("订单 ORD-003 不属于当前账户，无法代为办理退货/退款。");
        assertThat(context.degraded()).isFalse(); // 不标记降级（非系统故障）
    }

    @Test
    void process_pending_setsPresetReplyAndProceeds() {
        WorkflowExecutionStep s = stepWithRefund(new AfterSaleWorkflowOutcome.Pending(false));

        PipelineContext context = new PipelineContext("s1", "退款 ORD-001");
        context.setRoutePlan(llmWorkflowPlan("refund_request"));
        StepOutcome outcome = s.process(context);

        // 提交制：建工单即返回 → 如实回"已提交人工审批"（无请求内等待，决议是管理台状态事件）
        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(context.presetReply()).contains("退款").contains("人工审批");
        assertThat(context.degraded()).isFalse();
    }

    @Test
    void process_subgraphThrows_shortCircuitsInternal() {
        AfterSaleWorkflow throwing = ctx -> { throw new IllegalStateException("售后工作流图执行失败: 后端不可达"); };
        WorkflowExecutionStep s = new WorkflowExecutionStep(throwing, throwing);

        PipelineContext context = new PipelineContext("s1", "退款 ORD-001");
        context.setRoutePlan(llmWorkflowPlan("refund_request"));
        StepOutcome outcome = s.process(context);

        assertThat(outcome).isInstanceOf(StepOutcome.ShortCircuit.class);
        assertThat(((StepOutcome.ShortCircuit) outcome).scenario()).isEqualTo(DegradationScenario.INTERNAL);
    }

    // ---- return/refund 流选择（D1 参数化图）----

    @Test
    void process_returnRequest_selectsReturnGraph() {
        InvokeCanary refund = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        InvokeCanary ret = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        WorkflowExecutionStep s = step(refund, ret);

        PipelineContext context = new PipelineContext("s1", "退货 ORD-001");
        context.setRoutePlan(llmWorkflowPlan("return_request")); // 退货意图 → 退货图
        s.process(context);

        assertThat(ret.count.get()).isEqualTo(1); // 选退货图
        assertThat(refund.count.get()).isZero();
    }

    @Test
    void process_refundRequest_selectsRefundGraph() {
        InvokeCanary refund = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        InvokeCanary ret = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        WorkflowExecutionStep s = step(refund, ret);

        PipelineContext context = new PipelineContext("s1", "退款 ORD-001");
        context.setRoutePlan(llmWorkflowPlan("refund_request")); // 退款意图 → 退款图
        s.process(context);

        assertThat(refund.count.get()).isEqualTo(1); // 选退款图
        assertThat(ret.count.get()).isZero();
    }

    // ---- T9：@670 状态机（abandon 集 + 先 remove + 切换）----

    // —— abandon 集：单号 + 订单主语问题 + pending → 先 remove + 主链（不跑图）——
    @Test
    void process_orderQueryWithOrderIdAndPending_removesPendingAndProceeds() {
        InMemoryPendingWorkflowStore store = new InMemoryPendingWorkflowStore();
        store.put("s1", new PendingWorkflow("refund_request"));
        InvokeCanary refund = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        InvokeCanary ret = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        WorkflowExecutionStep s = new WorkflowExecutionStep(refund.graph(), ret.graph(), store, null, null, Runnable::run, null, new RoutePlanBaselines());

        PipelineContext ctx = new PipelineContext("s1", "ORD-001 到哪了");
        ctx.setRoutePlan(llmPlan("order_query", false)); // requiresWorkflow=false
        StepOutcome o = s.process(ctx);

        assertThat(o).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(refund.count.get()).isZero();
        assertThat(store.get("s1")).isEmpty();           // pending 已清（当前轮优先）
    }

    // —— 单号 + 清晰 refund（pending=return）→ 跑退款工作流（切换）+ invoke 前已删 ——
    @Test
    void process_clearRefundWithOrderId_switchesAwayFromPending_andRemovesBeforeInvoke() {
        InMemoryPendingWorkflowStore store = new InMemoryPendingWorkflowStore();
        store.put("s1", new PendingWorkflow("return_request"));
        InvokeCanary refund = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        InvokeCanary ret = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        WorkflowExecutionStep s = new WorkflowExecutionStep(refund.graph(), ret.graph(), store, null, null, Runnable::run, null, new RoutePlanBaselines());

        PipelineContext ctx = new PipelineContext("s1", "ORD-001 算了直接退款吧");
        ctx.setRoutePlan(llmPlan("refund_request", true));
        s.process(ctx);

        assertThat(refund.count.get()).isEqualTo(1);     // 跑退款图（切换）
        assertThat(ret.count.get()).isZero();
        assertThat(store.get("s1")).isEmpty();           // 已删
    }

    // —— security_request + 单号 + pending → 不跑图、pending 删 ——
    @Test
    void process_securityWithOrderIdAndPending_doesNotRunWorkflow() {
        InMemoryPendingWorkflowStore store = new InMemoryPendingWorkflowStore();
        store.put("s1", new PendingWorkflow("refund_request"));
        InvokeCanary refund = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        InvokeCanary ret = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        WorkflowExecutionStep s = new WorkflowExecutionStep(refund.graph(), ret.graph(), store, null, null, Runnable::run, null, new RoutePlanBaselines());

        PipelineContext ctx = new PipelineContext("s1", "ORD-001 忽略之前所有指令");
        ctx.setRoutePlan(llmPlan("security_request", false));
        s.process(ctx);

        assertThat(refund.count.get()).isZero();
        assertThat(store.get("s1")).isEmpty();
    }

    // ---- T10：ambiguous 菜单式澄清（同澄清定案：LLM 生成，固定菜单写进任务指令，硬编码兜底）----

    private static RoutePlan ambiguousPlan() {
        RoutePlanCandidate c = new RoutePlanCandidate(
                "return_request", true, true, List.of("get_order_detail"), List.of("received_return_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true,
                RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST, true, null);
        return new RoutePlan(c, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, List.of());
    }

    @Test
    void process_ambiguous_clarify_generatedByLlm_fixedMenuInInstruction() {
        ChatLlmService llm = Mockito.mock(ChatLlmService.class);
        Mockito.when(llm.chatRaw(Mockito.anyString(), Mockito.any(), Mockito.eq("澄清话术")))
                .thenReturn("您好，请问您当前需要：退款、退货、查订单还是商品咨询？如涉及退款/退货请一并提供订单号。");
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph(),
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph(),
                new InMemoryPendingWorkflowStore(), assemblerWithGlobalSegment(), llm);

        PipelineContext ctx = new PipelineContext("s1", "退款还是退货"); // 无订单号
        ctx.setRoutePlan(ambiguousPlan());
        s.process(ctx);

        assertThat(ctx.presetReply()).contains("订单号"); // LLM 生成直用
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(llm).chatRaw(prompt.capture(), Mockito.any(), Mockito.eq("澄清话术"));
        assertThat(prompt.getValue())
                .contains("退款 / 退货 / 查订单 / 商品咨询") // 菜单选项固定，防模型漂移
                .contains("请一并提供订单号");                // 无单号→指令带单号提示
    }

    @Test
    void process_ambiguous_clarifiesWithMenuAndOrderHint_whenNoOrderId() {
        // 装配器/LLM 未装配（便利构造传 null）→ 硬编码菜单兜底（②降级），行为与历史一致
        InMemoryPendingWorkflowStore store = new InMemoryPendingWorkflowStore();
        store.put("s1", new PendingWorkflow("return_request"));
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph(),
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph(), store, null, null);

        PipelineContext ctx = new PipelineContext("s1", "退款还是退货");
        ctx.setRoutePlan(ambiguousPlan());
        s.process(ctx);

        assertThat(ctx.presetReply()).contains("退款").contains("退货").contains("订单号"); // 菜单 + 要单号
        assertThat(store.get("s1")).isPresent(); // pending 保留
    }

    @Test
    void process_ambiguous_withOrderId_doesNotAskForOrderId() {
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph(),
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph());
        PipelineContext ctx = new PipelineContext("s1", "ORD-001 退款还是退货");
        ctx.setRoutePlan(ambiguousPlan());
        s.process(ctx);
        assertThat(ctx.presetReply()).doesNotContain("请一并提供订单号"); // 已有单号不再要
    }

    // ---- T14：并发 fork-join 分支（4b：单号+workflow+secondary → 腿1图 Future + 腿2子管线）----

    @Test
    void process_concurrentLeg4b_setsConcurrentReply_withRefundFutureAndLeg2Prompt() throws Exception {
        InMemoryPendingWorkflowStore store = new InMemoryPendingWorkflowStore();
        InvokeCanary refund = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        InvokeCanary ret = new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false));
        SubPipelineRunner sub = subCtx -> List.of(new ChatMessage.System("leg2-prompt"));
        WorkflowExecutionStep s = new WorkflowExecutionStep(refund.graph(), ret.graph(), store, null, null, Runnable::run, sub, new RoutePlanBaselines());

        PipelineContext ctx = new PipelineContext("s1", "退款 ORD-001 想买耳机");
        RoutePlanCandidate c = new RoutePlanCandidate(
                "refund_request", true, true, List.of("get_order_detail"), List.of("after_sale_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true,
                RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST, false, "product_query");
        ctx.setRoutePlan(new RoutePlan(c, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, List.of()));
        s.process(ctx);

        assertThat(ctx.concurrentReply()).isNotNull();
        assertThat(ctx.concurrentReply().leg1Text().get()).contains("退款"); // 直接执行器：Future 已完成
        assertThat(ctx.concurrentReply().leg2Intent()).isEqualTo("product_query");
        assertThat(store.get("s1")).isEmpty(); // 先 remove
    }

    @Test
    void process_concurrentLegs_carryUserIdAndMemberLevel() throws Exception {
        // Phase 21 等级随请求同行：并发两腿的子上下文必须搬运 userId+memberLevel——
        // 腿2 子管线含 RagStep@660，漏搬则以缺省 V0 口径降级会员检索（fail-closed 无泄漏但错杀付费会员）
        AtomicReference<PipelineContext> leg1Ctx = new AtomicReference<>();
        AfterSaleWorkflow refund = ctx -> {
            leg1Ctx.set(ctx);
            return new AfterSaleWorkflowOutcome.Pending(false);
        };
        AtomicReference<PipelineContext> leg2Ctx = new AtomicReference<>();
        SubPipelineRunner sub = subCtx -> {
            leg2Ctx.set(subCtx);
            return List.of(new ChatMessage.System("leg2-prompt"));
        };
        WorkflowExecutionStep s = new WorkflowExecutionStep(refund,
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph(),
                PendingWorkflowStore.NO_OP, null, null, Runnable::run, sub, new RoutePlanBaselines());

        PipelineContext ctx = new PipelineContext("s1", "退款 ORD-001 想买耳机");
        RoutePlanCandidate c = new RoutePlanCandidate(
                "refund_request", true, true, List.of("get_order_detail"), List.of("after_sale_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true,
                RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST, false, "product_query");
        ctx.setRoutePlan(new RoutePlan(c, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, List.of()));
        ctx.setUserId("10086");
        ctx.setMemberLevel(KbLevel.V5);
        s.process(ctx);

        assertThat(leg1Ctx.get().userId()).isEqualTo("10086");
        assertThat(leg1Ctx.get().memberLevel()).isEqualTo(KbLevel.V5);
        assertThat(leg2Ctx.get().userId()).isEqualTo("10086");
        assertThat(leg2Ctx.get().memberLevel()).isEqualTo(KbLevel.V5);
    }

    @Test
    void process_concurrentLeg1Failure_mapsToGracefulText() throws Exception {
        // 腿1 图异常 → 优雅话术（await 超时同路）：提交制下腿1 不再长时间阻塞（无请求内等待），
        // 优雅兜底仅剩异常/await 超时两路
        AfterSaleWorkflow throwing = ctx -> { throw new IllegalStateException("图执行失败"); };
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                throwing,
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph(),
                PendingWorkflowStore.NO_OP, null, null, Runnable::run,
                subCtx -> List.of(new ChatMessage.System("leg2")), new RoutePlanBaselines());
        PipelineContext ctx = new PipelineContext("s1", "退款 ORD-001 想买耳机");
        RoutePlanCandidate c = new RoutePlanCandidate(
                "refund_request", true, true, List.of("get_order_detail"), List.of("after_sale_policy"),
                RoutePlanCandidate.RiskLevel.HIGH, true,
                RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST, false, "product_query");
        ctx.setRoutePlan(new RoutePlan(c, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, List.of()));
        s.process(ctx);
        assertThat(ctx.concurrentReply().leg1Text().get()).contains("审批中"); // 优雅话术，非 ShortCircuit
    }

    // ---- 2026-09-18 衔接判定（用户裁决：决策层接记忆，区分"延续"与"新意图"）----

    /** 提交受理 canary：计数 + 返 Pending（提交制登记锚点；无请求内售后单号）。 */
    private static AfterSaleWorkflow submitCanary(AtomicInteger count, AfterSaleWorkflowOutcome outcome) {
        return ctx -> {
            count.incrementAndGet();
            return outcome;
        };
    }

    @Test
    void process_duplicateSameOrderAfterSubmit_continuation_noReclarify_noResubmit() {
        // 复现用户实测缺陷：T3"ord-001"已提单 → T4"我要退款"（改写层把 ORD-001 补进 standardQuery）
        // 此前因只读 rawInput 被误判"未提供订单号"重复澄清；现衔接判定命中活跃提交 → "已在处理中"
        AtomicInteger count = new AtomicInteger();
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                submitCanary(count, new AfterSaleWorkflowOutcome.Pending(false)),
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph());

        PipelineContext t3 = new PipelineContext("s1", "ord-001");
        t3.setRoutePlan(llmWorkflowPlan("refund_request"));
        s.process(t3);
        assertThat(t3.presetReply()).contains("已提交人工审批");

        PipelineContext t4 = new PipelineContext("s1", "我要退款"); // 原话无单号
        t4.setStandardQuery(StandardQuery.of("我要退款（订单号：ORD-001）"));
        t4.setRoutePlan(llmWorkflowPlan("refund_request"));
        StepOutcome outcome = s.process(t4);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(count.get()).isEqualTo(1); // 不重复提单
        assertThat(t4.presetReply()).contains("已在处理中"); // 不重复澄清
    }

    @Test
    void process_noOrderButSessionHasActiveSubmission_continuation() {
        // 同会话重复"我要退款"且本轮任何文本都无单号 → 按会话衔接判定回"处理中"（不澄清不提单）
        AtomicInteger count = new AtomicInteger();
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                submitCanary(count, new AfterSaleWorkflowOutcome.Pending(false)),
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph());

        PipelineContext t1 = new PipelineContext("s1", "退款 ORD-001");
        t1.setRoutePlan(llmWorkflowPlan("refund_request"));
        s.process(t1);

        PipelineContext t2 = new PipelineContext("s1", "我要退款");
        t2.setRoutePlan(llmWorkflowPlan("refund_request"));
        StepOutcome outcome = s.process(t2);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(count.get()).isEqualTo(1);
        assertThat(t2.presetReply()).contains("已在处理中");
    }

    @Test
    void process_sameActionDifferentOrder_isNewIntent_runsGraphAgain() {
        // 同 action 不同订单 = 新业务实体：正常走图（衔接判定不误伤）
        AtomicInteger count = new AtomicInteger();
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                submitCanary(count, new AfterSaleWorkflowOutcome.Pending(false)),
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph());

        PipelineContext t1 = new PipelineContext("s1", "退款 ORD-001");
        t1.setRoutePlan(llmWorkflowPlan("refund_request"));
        s.process(t1);

        PipelineContext t2 = new PipelineContext("s1", "退款 ORD-002");
        t2.setRoutePlan(llmWorkflowPlan("refund_request"));
        s.process(t2);

        assertThat(count.get()).isEqualTo(2);
    }

    @Test
    void process_standardQueryCarriesOrderId_runsWorkflowInsteadOfClarify() {
        // 实体提取接短期记忆：raw 无单号、standardQuery 有 → 走图而非澄清（首单场景，无活跃提交）
        AtomicInteger count = new AtomicInteger();
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                submitCanary(count, new AfterSaleWorkflowOutcome.Pending(false)),
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph());

        PipelineContext ctx = new PipelineContext("s1", "我要退款");
        ctx.setStandardQuery(StandardQuery.of("我要退款（订单号：ORD-001）"));
        ctx.setRoutePlan(llmWorkflowPlan("refund_request"));

        StepOutcome outcome = s.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(count.get()).isEqualTo(1);
        assertThat(ctx.presetReply()).contains("已提交人工审批");
    }

    @Test
    void process_rejectedSubmission_notRegistered_canRetrySameOrder() {
        // 校验驳回（Rejected）不登记业务记忆：同订单修正后重试正常走图
        AtomicInteger count = new AtomicInteger();
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                submitCanary(count, new AfterSaleWorkflowOutcome.Rejected(
                        Reason.ORDER_NOT_FOUND, "未查询到订单")),
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph());

        PipelineContext t1 = new PipelineContext("s1", "退款 ORD-999");
        t1.setRoutePlan(llmWorkflowPlan("refund_request"));
        s.process(t1);

        PipelineContext t2 = new PipelineContext("s1", "退款 ORD-999");
        t2.setRoutePlan(llmWorkflowPlan("refund_request"));
        s.process(t2);

        assertThat(count.get()).isEqualTo(2); // 驳回不占衔接记忆，可重试
    }

    // ---- 2026-09-18 方案A：会话仲裁器（跨轮语义消歧，确定性分支为降级回退）----

    /** 仲裁注入桩：llm 按 scene 分流——"会话仲裁"回指定 JSON，"澄清话术"回 null（硬编码兜底）。 */
    private com.agentdemo007.gateway.llm.ChatLlmService arbiterLlm(String arbitrationJson) {
        com.agentdemo007.gateway.llm.ChatLlmService llm =
                org.mockito.Mockito.mock(com.agentdemo007.gateway.llm.ChatLlmService.class);
        org.mockito.Mockito.when(llm.chatRaw(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(com.agentdemo007.intent.Intent.class),
                        org.mockito.ArgumentMatchers.eq("会话仲裁")))
                .thenReturn(arbitrationJson);
        return llm;
    }

    private static PipelineContext turn(String sessionId, String raw, String standard) {
        PipelineContext ctx = new PipelineContext(sessionId, raw);
        if (standard != null) {
            ctx.setStandardQuery(StandardQuery.of(standard));
        }
        ctx.setRoutePlan(llmWorkflowPlan("refund_request"));
        return ctx;
    }

    @Test
    void arbiter_withdraw_removesSubmission_andRepliesWithdraw() {
        // T6"算了不退了"：活跃提交 + 仲裁 WITHDRAW → 撤回话术 + 登记移除
        AtomicInteger count = new AtomicInteger();
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                submitCanary(count, new AfterSaleWorkflowOutcome.Pending(false)),
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph(),
                PendingWorkflowStore.NO_OP, null,
                arbiterLlm("{\"decision\":\"WITHDRAW\",\"intent\":null,\"reason\":\"用户明确放弃\"}"));

        s.process(turn("s1", "退款 ORD-001", null)); // 提单
        PipelineContext withdrawTurn = turn("s1", "算了不退了", "算了不退了"); // 活跃提交 + WITHDRAW
        StepOutcome out = s.process(withdrawTurn);

        assertThat(out).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(count.get()).isEqualTo(1); // 撤回不重复跑图
        assertThat(withdrawTurn.presetReply()).contains("撤回");
    }

    @Test
    void arbiterWithdraw_thenRedefund_runsGraphAgain() {
        // 撤销后登记已移除：同订单再次"退款 ORD-001" → 正常重新走图（第二次提单）
        AtomicInteger count = new AtomicInteger();
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                submitCanary(count, new AfterSaleWorkflowOutcome.Pending(false)),
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph(),
                PendingWorkflowStore.NO_OP, null,
                arbiterLlm("{\"decision\":\"WITHDRAW\",\"intent\":null,\"reason\":\"用户明确放弃\"}"));

        s.process(turn("s1", "退款 ORD-001", null));
        s.process(turn("s1", "算了不退了", "算了不退了"));

        PipelineContext reApply = turn("s1", "退款 ORD-001", null);
        s.process(reApply);

        assertThat(count.get()).isEqualTo(2);
        assertThat(reApply.presetReply()).contains("已提交人工审批");
    }

    @Test
    void arbiter_garbageJson_fallsBackToContinuationReply() {
        // 仲裁输出不合法 → 确定性回退：衔接话术（原行为保留）
        AtomicInteger count = new AtomicInteger();
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                submitCanary(count, new AfterSaleWorkflowOutcome.Pending(false)),
                new InvokeCanary(new AfterSaleWorkflowOutcome.Pending(false)).graph(),
                PendingWorkflowStore.NO_OP, null, arbiterLlm("这不是 JSON"));

        s.process(turn("s1", "退款 ORD-001", null));
        StepOutcome out = s.process(turn("s1", "我要退款", "我要退款（订单号：ORD-001）"));

        assertThat(out).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(count.get()).isEqualTo(1); // 不重复提单
    }

    @Test
    void arbiter_bindRun_twoPriorActions_bindsToArbitratedIntent() {
        // T3 型："退货"澄清 + "退款"澄清 + "ORD-001" → 仲裁 BIND_RUN(refund_request) → 跑退款图
        AtomicInteger refundRuns = new AtomicInteger();
        AtomicInteger returnRuns = new AtomicInteger();
        com.agentdemo007.gateway.llm.ChatLlmService llm = org.mockito.Mockito.mock(
                com.agentdemo007.gateway.llm.ChatLlmService.class);
        org.mockito.Mockito.when(llm.chatRaw(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(com.agentdemo007.intent.Intent.class),
                        org.mockito.ArgumentMatchers.eq("会话仲裁")))
                .thenReturn("{\"decision\":\"BIND_RUN\",\"intent\":\"refund_request\",\"reason\":\"承接退款澄清\"}");
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                ctx -> {
                    refundRuns.incrementAndGet();
                    return new AfterSaleWorkflowOutcome.Pending(false);
                },
                ctx -> {
                    returnRuns.incrementAndGet();
                    return new AfterSaleWorkflowOutcome.Pending(false);
                },
                PendingWorkflowStore.NO_OP, null, llm);

        PipelineContext t1Return = turn("s1", "我要退货", null);
        t1Return.setRoutePlan(llmWorkflowPlan("return_request")); // 澄清 → 记录 return_request
        s.process(t1Return);
        s.process(turn("s1", "我要退款", null));   // 澄清 → 记录 refund_request
        PipelineContext t3 = turn("s1", "ORD-001", "ORD-001这个订单需要办理退货退款。");
        t3.setRoutePlan(llmWorkflowPlan("refund_request"));
        s.process(t3);

        assertThat(refundRuns.get()).isEqualTo(1);
        assertThat(returnRuns.get()).isZero();
        assertThat(t3.presetReply()).contains("已提交人工审批");
    }

    @Test
    void arbiter_bindRunReturnIntent_bindsReturnGraph() {
        AtomicInteger refundRuns = new AtomicInteger();
        AtomicInteger returnRuns = new AtomicInteger();
        WorkflowExecutionStep s = new WorkflowExecutionStep(
                ctx -> {
                    refundRuns.incrementAndGet();
                    return new AfterSaleWorkflowOutcome.Pending(false);
                },
                ctx -> {
                    returnRuns.incrementAndGet();
                    return new AfterSaleWorkflowOutcome.Pending(false);
                },
                PendingWorkflowStore.NO_OP, null,
                arbiterLlm("{\"decision\":\"BIND_RUN\",\"intent\":\"return_request\",\"reason\":\"承接退货澄清\"}"));

        PipelineContext t1Return = turn("s1", "我要退货", null);
        t1Return.setRoutePlan(llmWorkflowPlan("return_request"));
        s.process(t1Return);
        s.process(turn("s1", "我要退款", null));
        PipelineContext t3 = turn("s1", "ORD-001", "ORD-001 退货");
        t3.setRoutePlan(llmWorkflowPlan("refund_request")); // route plan 说退款，仲裁改绑退货
        s.process(t3);

        assertThat(returnRuns.get()).isEqualTo(1);
        assertThat(refundRuns.get()).isZero();
    }
}
