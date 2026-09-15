package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.business.MockPolicyQueryService;
import com.agentdemo007.capability.business.OrderQueryService;
import com.agentdemo007.capability.business.PolicyDomain;
import com.agentdemo007.capability.business.PolicyQueryService;
import com.agentdemo007.capability.business.UserQueryService;
import com.agentdemo007.common.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AfterSaleWorkflowGraph} 5+ 节点真 DAG 单测（Slice 3·[[business-tools-workflow-dag]] §2.3·
 * "将 DAG 确实落到 Workflow"）。
 *
 * <p>固定 6 节点 DAG（替 2 节点 {@link RefundWorkflowGraph}）：
 * {@code query_user → query_order → query_policy → validate →(条件边)
 * pass: submit_approval → approval_gate → END；fail: → END(Rejected(reason,message))}。
 *
 * <p>验 4 校验分支（用户钦定"校验失败按情况告知客户"）：
 * <ul>
 *   <li>本人+7 天内+订单存在（ORD-001）→ pass → submit+approval → Approved；</li>
 *   <li>非本人（ORD-003, 10010≠当前 10086）→ Rejected(ORDER_NOT_OWNED)，校验失败不提交；</li>
 *   <li>超 7 天（ORD-002, 11 天前）→ Rejected(BEYOND_7_DAY)；</li>
 *   <li>订单不存在（ORD-999）→ Rejected(ORDER_NOT_FOUND)；</li>
 *   <li>approval Timeout → Timeout 终态（submit 已跑）；</li>
 *   <li>approval Denied → submit Retry（maxIterations 护栏）。</li>
 * </ul>
 * seams 用 lambda（submit 返 id、approval 按内容决议）；时钟固定 2026-09-12 钉 7 天窗口判定（[[code-review-hardening-pass]]
 * 同款 LongSupplier/固定 Clock 确定性）。真退款/退货后端 + 真人工审批 = Slice 4 延后。
 */
class AfterSaleWorkflowGraphTest {

    /** 固定时钟 2026-09-12（钉 7 天窗口：ORD-001 3 天前=窗口内、ORD-002 11 天前=超窗）。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);

    private final UserQueryService userService = new UserQueryService();
    private final OrderQueryService orderService = new OrderQueryService();
    private final PolicyQueryService policyService = new MockPolicyQueryService();

    /** 退货图（PolicyDomain.RETURN + ReturnValidationRule，当前账户 10086）。 */
    private AfterSaleWorkflowGraph returnGraph(WorkflowApprovalDecision approval, AfterSaleSubmitService submit) {
        return new AfterSaleWorkflowGraph(userService, orderService, policyService,
                PolicyDomain.RETURN, new ReturnValidationRule(CLOCK),
                submit, approval, "10086");
    }

    private static WorkflowApprovalDecision autoApprove() {
        return wr -> new WorkflowApprovalDecision.Approved("auto");
    }

    private static AfterSaleSubmitService submitReturning(String id) {
        return ctx -> id;
    }

    // ---- pass 路径 ----

    @Test
    void pass_ownedWithinWindow_submitsAndApproves() {
        AtomicInteger submitCount = new AtomicInteger();
        AfterSaleSubmitService submit = ctx -> {
            submitCount.incrementAndGet();
            return "WF-RET-001";
        };
        AfterSaleWorkflowGraph graph = returnGraph(autoApprove(), submit);

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退货 ORD-001"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Approved.class);
        assertThat(submitCount.get()).isEqualTo(1); // submit 跑一次
    }

    // ---- fail 路径：校验失败 → Rejected(reason, message)，不提交 ----

    @Test
    void fail_orderNotOwned_rejectedWithoutSubmit() {
        AtomicInteger submitCount = new AtomicInteger();
        AfterSaleSubmitService submit = ctx -> {
            submitCount.incrementAndGet();
            return "WF-RET-003";
        };
        AfterSaleWorkflowGraph graph = returnGraph(autoApprove(), submit);

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退货 ORD-003"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Rejected.class);
        AfterSaleWorkflowOutcome.Rejected r = (AfterSaleWorkflowOutcome.Rejected) outcome;
        assertThat(r.reason()).isEqualTo(Reason.ORDER_NOT_OWNED);
        assertThat(r.customerMessage()).contains("不属于");
        assertThat(submitCount.get()).isZero(); // 校验失败不提交（不进入 submit_approval）
    }

    @Test
    void fail_beyond7Day_rejected() {
        AfterSaleWorkflowGraph graph = returnGraph(autoApprove(), submitReturning("WF-RET-002"));

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退货 ORD-002"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Rejected.class);
        assertThat(((AfterSaleWorkflowOutcome.Rejected) outcome).reason()).isEqualTo(Reason.BEYOND_7_DAY);
        assertThat(((AfterSaleWorkflowOutcome.Rejected) outcome).customerMessage()).contains("7天");
    }

    @Test
    void fail_orderNotFound_rejected() {
        AfterSaleWorkflowGraph graph = returnGraph(autoApprove(), submitReturning("WF-RET-999"));

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退货 ORD-999"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Rejected.class);
        assertThat(((AfterSaleWorkflowOutcome.Rejected) outcome).reason()).isEqualTo(Reason.ORDER_NOT_FOUND);
        assertThat(((AfterSaleWorkflowOutcome.Rejected) outcome).customerMessage()).contains("ORD-999");
    }

    // ---- ctx.userId 覆盖 baked currentUserId（per-request 真实用户·[[business-tools-workflow-dag]] 真接入）----

    @Test
    void ctxUserId_overridesBakedCurrentUserId_validatesAgainstRealUser() {
        // 图构造期 baked currentUserId=10086（mock 兜底）；ctx.userId=10010（前端 ChatRequest 传入·per-request）
        // 优先于 baked → 校验对真实用户 10010 做。ORD-001 属 10086 → 10010≠10086 → ORDER_NOT_OWNED。
        AfterSaleWorkflowGraph graph = returnGraph(autoApprove(), submitReturning("WF-RET-10010"));

        PipelineContext ctx = new PipelineContext("s1", "退货 ORD-001");
        ctx.setUserId("10010"); // 真实用户 10010（非图 baked 10086，查他人单→驳回）
        AfterSaleWorkflowOutcome outcome = graph.invoke(ctx);

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Rejected.class);
        assertThat(((AfterSaleWorkflowOutcome.Rejected) outcome).reason()).isEqualTo(Reason.ORDER_NOT_OWNED);
    }

    // ---- [[business-tools-workflow-dag]] 数据准确性：mock 须对得上前端真实用户 ----

    @Test
    void demoUser_10086_ownOrder_validatesPass_notOrderNotOwned() {
        // 用户钦定"工具调用对应数据必须准确"：前端真实用户 userId=10086 / vipLevel=gold 须在 mock 存在，
        // 且其自有订单 ORD-001 校验为 pass（Approved），而非误判 ORDER_NOT_OWNED。
        // 修前 mock 种子 U100≠10086 → 真实用户查自己的单被误判非本人（ORDER_NOT_OWNED）。本测 RED 驱动重播种。
        AfterSaleWorkflowGraph graph = returnGraph(autoApprove(), submitReturning("WF-RET-10086"));
        PipelineContext ctx = new PipelineContext("s1", "退货 ORD-001");
        ctx.setUserId("10086"); // 前端真实用户（per-request·ChatRequest.userId）
        AfterSaleWorkflowOutcome outcome = graph.invoke(ctx);

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Approved.class);
    }

    // ---- approval 终态 ----

    @Test
    void approvalTimeout_returnsTimeout() {
        // validate pass（ORD-001）→ submit → approval Timeout → END
        WorkflowApprovalDecision timeout = wr -> new WorkflowApprovalDecision.Timeout();
        AfterSaleWorkflowGraph graph = returnGraph(timeout, submitReturning("WF-RET-T1"));

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退货 ORD-001"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Timeout.class);
    }

    @Test
    void approvalDeniedThenApproved_retriesSubmitThenEnds() {
        // approval 首次驳回→Retry 回 submit 改写重提，二次批准→END（镜像 RefundWorkflowGraphTest Slice 2）
        AtomicInteger submitCount = new AtomicInteger();
        AfterSaleSubmitService submit = ctx -> "WF-RET-D" + submitCount.incrementAndGet();
        WorkflowApprovalDecision denyThenApprove = wr ->
                "WF-RET-D1".equals(wr)
                        ? new WorkflowApprovalDecision.Denied("首次驳回·改写重提")
                        : new WorkflowApprovalDecision.Approved("auto");
        AfterSaleWorkflowGraph graph = returnGraph(denyThenApprove, submit);

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退货 ORD-001"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Approved.class);
        assertThat(submitCount.get()).isEqualTo(2); // 驳回→Retry→二次提交→批准→END
    }

    // ---- 退款流：RefundValidationRule + PolicyDomain.REFUND ----

    @Test
    void refundFlow_ownedWithinWindow_approved() {
        AtomicInteger submitCount = new AtomicInteger();
        AfterSaleSubmitService submit = ctx -> {
            submitCount.incrementAndGet();
            return "WF-RFD-001";
        };
        // 退款图：REFUND 域 + RefundValidationRule（退款窗口 30 天，ORD-001 3 天前在窗内）
        AfterSaleWorkflowGraph graph = new AfterSaleWorkflowGraph(userService, orderService, policyService,
                PolicyDomain.REFUND, new RefundValidationRule(CLOCK),
                submit, autoApprove(), "10086");

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退款 ORD-001"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Approved.class);
        assertThat(submitCount.get()).isEqualTo(1);
    }

    @Test
    void refundFlow_beyondWindow_rejectedRefundWindowExpired() {
        // ORD-002 11 天前 → 退款窗口 30 天内 → 仍 pass（退款窗口比退货宽）
        // 验退款流用 REFUND_WINDOW_EXPIRED 语义：构造超 30 天订单须另备数据；此处验 7 天外但 30 天内=pass
        // 改用 ORD-001（3 天前）+ 退款窗口收缩到 2 天验证 REFUND_WINDOW_EXPIRED 分支
        AfterSaleWorkflowGraph graph = new AfterSaleWorkflowGraph(userService, orderService, policyService,
                PolicyDomain.REFUND, new RefundValidationRule(CLOCK, java.time.Duration.ofDays(2)),
                submitReturning("WF-RFD-EXP"), autoApprove(), "10086");

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退款 ORD-001"));

        // ORD-001 3 天前 > 2 天退款窗口 → REFUND_WINDOW_EXPIRED
        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Rejected.class);
        assertThat(((AfterSaleWorkflowOutcome.Rejected) outcome).reason()).isEqualTo(Reason.REFUND_WINDOW_EXPIRED);
    }

    @Test
    void extractOrderId_matchesLowercaseAndNoDashVariants() {
        assertThat(AfterSaleWorkflowGraph.extractOrderIdFrom("退款 ord0001")).isEqualTo("ord0001");
        assertThat(AfterSaleWorkflowGraph.extractOrderIdFrom("退款 ORD001")).isEqualTo("ORD001");
        assertThat(AfterSaleWorkflowGraph.extractOrderIdFrom("退款 ord-001")).isEqualTo("ord-001");
        assertThat(AfterSaleWorkflowGraph.extractOrderIdFrom("退款 ORD-001")).isEqualTo("ORD-001");
    }
}
