package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.business.MockPolicyQueryService;
import com.agentdemo007.capability.business.OrderQueryService;
import com.agentdemo007.capability.business.PolicyDomain;
import com.agentdemo007.capability.business.PolicyQueryService;
import com.agentdemo007.capability.business.UserQueryService;
import com.agentdemo007.common.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AfterSaleWorkflowGraph} 真 DAG 单测（[[business-tools-workflow-dag]] §2.3·2026-09-20 提交制收缩）。
 *
 * <p>固定 5 节点 DAG：{@code query_user → query_order → query_policy → validate →(条件边)
 * pass: submit_ticket（建人工工单即返回）→ END(Pending)；fail: → END(Rejected(reason,message))}。
 * <b>原则（用户裁决）：审批是事件、Agent 最小权限</b>——图内无审批等待：validate pass 后建 PENDING
 * 人工工单立即返回 {@link AfterSaleWorkflowOutcome.Pending}；批准/驳回是管理台的工单状态事件，
 * 由 {@code AdminHitlController} 决议（批准触发 {@code AfterSaleBusinessExecutor} 业务执行 mock）；
 * 客户后续经工单查询工具读进度。
 *
 * <p>验分支：本人+订单存在（ORD-001）→ pass → 建单 → Pending；非本人（ORD-003, 10010≠10086）→
 * Rejected(ORDER_NOT_OWNED) 且不建单；订单不存在（ORD-999）→ Rejected(ORDER_NOT_FOUND)；
 * Agent 裁决 INELIGIBLE → Rejected(POLICY_INELIGIBLE)（话术=Agent 产出，不建单）；
 * UNCERTAIN → fail-safe 到人工建单；alreadyApproved 提交结果 → Pending(true)。
 * seams 用 lambda（submitter 计数返固定结果、judge 三态桩）；时钟对裁决无影响（裁决器自持 Clock）。
 */
class AfterSaleWorkflowGraphTest {

    private final UserQueryService userService = new UserQueryService();
    private final OrderQueryService orderService = new OrderQueryService();
    private final PolicyQueryService policyService = new MockPolicyQueryService();

    /** 计数提交器：记 submit 次数 + 返回提交结果（TK-TEST-{n}，可指定 alreadyApproved）。 */
    private static final class SubmitCanary implements WorkflowApprovalSubmitter {
        final AtomicInteger count = new AtomicInteger();
        final boolean alreadyApproved;

        SubmitCanary(boolean alreadyApproved) { this.alreadyApproved = alreadyApproved; }

        @Override
        public Outcome submit(ApprovalRequest request) {
            return new Outcome("TK-TEST-" + count.incrementAndGet(), alreadyApproved);
        }
    }

    private static AfterSaleEligibilityJudge judgeEligible() {
        return input -> new AfterSaleEligibilityJudge.Verdict(
                AfterSaleEligibilityJudge.Decision.ELIGIBLE, "7 天无理由退货期内", null);
    }

    private static AfterSaleEligibilityJudge judgeIneligible(String message) {
        return input -> new AfterSaleEligibilityJudge.Verdict(
                AfterSaleEligibilityJudge.Decision.INELIGIBLE, "超无理由期限", message);
    }

    private static AfterSaleEligibilityJudge judgeUncertain() {
        return input -> AfterSaleEligibilityJudge.Verdict.uncertain("政策未覆盖");
    }

    /** 退货图（PolicyDomain.RETURN，当前账户 10086，提交器可注入）。 */
    private AfterSaleWorkflowGraph returnGraph(WorkflowApprovalSubmitter submitter,
                                               AfterSaleEligibilityJudge judge) {
        return new AfterSaleWorkflowGraph(userService, orderService, policyService,
                PolicyDomain.RETURN, judge, submitter, "10086");
    }

    // ---- pass 路径：建单即返回（提交制） ----

    @Test
    void pass_ownedOrder_submitsTicket_returnsPending() {
        SubmitCanary submitter = new SubmitCanary(false);
        AfterSaleWorkflowGraph graph = returnGraph(submitter, judgeEligible());

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退货 ORD-001"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Pending.class);
        assertThat(((AfterSaleWorkflowOutcome.Pending) outcome).alreadyApproved()).isFalse();
        assertThat(submitter.count.get()).isEqualTo(1); // 建单恰一次
    }

    @Test
    void pass_lowercaseOrderId_normalizedToUpper() {
        // 2026-09-17 定案回归钉：用户手打小写 "ord-001" —— (?i) 匹配但 group() 原样返回小写，
        // 精确键查找 miss → 误判「未查询到订单」。提取点统一归一化大写。
        SubmitCanary submitter = new SubmitCanary(false);
        AfterSaleWorkflowGraph graph = returnGraph(submitter, judgeEligible());

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退货 ord-001"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Pending.class);
        assertThat(submitter.count.get()).isEqualTo(1);
    }

    @Test
    void submitTicket_carriesFullContext_adminOneScreenReview() {
        // 提交制：工单上下文全量富集（动作/订单/订单实时事实摘要/Agent 资格判定），管理员一屏可审
        List<WorkflowApprovalSubmitter.ApprovalRequest> seen = new ArrayList<>();
        AfterSaleWorkflowGraph graph = returnGraph(req -> {
            seen.add(req);
            return new WorkflowApprovalSubmitter.Outcome("TK-1", false);
        }, judgeEligible());

        graph.invoke(new PipelineContext("s1", "退货 ORD-001"));

        assertThat(seen).hasSize(1);
        WorkflowApprovalSubmitter.ApprovalRequest req = seen.get(0);
        assertThat(req.action()).isEqualTo("RETURN");
        assertThat(req.orderId()).isEqualTo("ORD-001");
        assertThat(req.orderSummary()).contains("10086").contains("已签收").contains("299.00"); // 实时事实摘要
        assertThat(req.eligibilityNote()).contains("ELIGIBLE"); // Agent 裁决进工单
        assertThat(req.sessionId()).isEqualTo("s1");
        assertThat(req.userQuery()).isEqualTo("退货 ORD-001");
    }

    @Test
    void pass_alreadyApprovedOutcome_mapsToPendingTrue() {
        // 提交制幂等：同键工单此前已批准（submitter 返 alreadyApproved=true）→ Pending(true)
        AfterSaleWorkflowGraph graph = returnGraph(new SubmitCanary(true), judgeEligible());

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退货 ORD-001"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Pending.class);
        assertThat(((AfterSaleWorkflowOutcome.Pending) outcome).alreadyApproved()).isTrue();
    }

    // ---- fail 路径：机械事实校验（先于 Agent，短路即驳、不建单） ----

    @Test
    void fail_orderNotOwned_rejectedWithoutSubmit() {
        SubmitCanary submitter = new SubmitCanary(false);
        AfterSaleWorkflowGraph graph = returnGraph(submitter, judgeEligible());

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退货 ORD-003"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Rejected.class);
        AfterSaleWorkflowOutcome.Rejected r = (AfterSaleWorkflowOutcome.Rejected) outcome;
        assertThat(r.reason()).isEqualTo(Reason.ORDER_NOT_OWNED);
        assertThat(r.customerMessage()).contains("不属于");
        assertThat(submitter.count.get()).isZero(); // 校验失败不建单（不进入 submit_ticket）
    }

    @Test
    void fail_orderNotFound_rejected() {
        SubmitCanary submitter = new SubmitCanary(false);
        AfterSaleWorkflowGraph graph = returnGraph(submitter, judgeEligible());

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退货 ORD-999"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Rejected.class);
        assertThat(((AfterSaleWorkflowOutcome.Rejected) outcome).reason()).isEqualTo(Reason.ORDER_NOT_FOUND);
        assertThat(((AfterSaleWorkflowOutcome.Rejected) outcome).customerMessage()).contains("ORD-999");
        assertThat(submitter.count.get()).isZero();
    }

    // ---- fail 路径：Agent 资格裁决 INELIGIBLE（政策类驳回，话术=Agent 产出，不建单） ----

    @Test
    void fail_agentIneligible_rejectedPolicyReason_agentMessage_noTicket() {
        SubmitCanary submitter = new SubmitCanary(false);
        AfterSaleWorkflowGraph graph = returnGraph(submitter,
                judgeIneligible("您的订单已超过7天无理由退货期限，如遇质量问题可联系人工客服。"));

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退货 ORD-002"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Rejected.class);
        AfterSaleWorkflowOutcome.Rejected r = (AfterSaleWorkflowOutcome.Rejected) outcome;
        assertThat(r.reason()).isEqualTo(Reason.POLICY_INELIGIBLE);
        assertThat(r.customerMessage()).contains("7天无理由退货期限").contains("人工客服");
        assertThat(submitter.count.get()).isZero(); // 资格不满足不建单
    }

    @Test
    void agentUncertain_failsafeToHumanTicket() {
        // fail-safe（2026-09-19 定案）：裁决 UNCERTAIN（政策未覆盖/模型失败）不冒充驳回、不盲放行 →
        // 建人工工单交管理员重点复核
        SubmitCanary submitter = new SubmitCanary(false);
        AfterSaleWorkflowGraph graph = returnGraph(submitter, judgeUncertain());

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退货 ORD-001"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Pending.class);
        assertThat(submitter.count.get()).isEqualTo(1);
    }

    // ---- ctx.userId 覆盖 baked currentUserId（per-request 真实用户·[[business-tools-workflow-dag]] 真接入）----

    @Test
    void ctxUserId_overridesBakedCurrentUserId_validatesAgainstRealUser() {
        // 图构造期 baked currentUserId=10086（mock 兜底）；ctx.userId=10010（前端 ChatRequest 传入·per-request）
        // 优先于 baked → 校验对真实用户 10010 做。ORD-001 属 10086 → 10010≠10086 → ORDER_NOT_OWNED。
        SubmitCanary submitter = new SubmitCanary(false);
        AfterSaleWorkflowGraph graph = returnGraph(submitter, judgeEligible());

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
        // 且其自有订单 ORD-001 校验为 pass（建单提交），而非误判 ORDER_NOT_OWNED。
        SubmitCanary submitter = new SubmitCanary(false);
        AfterSaleWorkflowGraph graph = returnGraph(submitter, judgeEligible());
        PipelineContext ctx = new PipelineContext("s1", "退货 ORD-001");
        ctx.setUserId("10086"); // 前端真实用户（per-request·ChatRequest.userId）
        AfterSaleWorkflowOutcome outcome = graph.invoke(ctx);

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Pending.class);
    }

    // ---- 退款流：PolicyDomain.REFUND ----

    @Test
    void refundFlow_ownedOrder_agentEligible_submitted() {
        SubmitCanary submitter = new SubmitCanary(false);
        AfterSaleWorkflowGraph graph = new AfterSaleWorkflowGraph(userService, orderService, policyService,
                PolicyDomain.REFUND, judgeEligible(), submitter, "10086");

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退款 ORD-001"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Pending.class);
        assertThat(submitter.count.get()).isEqualTo(1);
    }

    @Test
    void refundFlow_agentIneligible_rejected() {
        SubmitCanary submitter = new SubmitCanary(false);
        AfterSaleWorkflowGraph graph = new AfterSaleWorkflowGraph(userService, orderService, policyService,
                PolicyDomain.REFUND, judgeIneligible("您的订单按平台退款政策不符合办理条件。"), submitter, "10086");

        AfterSaleWorkflowOutcome outcome = graph.invoke(new PipelineContext("s1", "退款 ORD-001"));

        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Rejected.class);
        assertThat(((AfterSaleWorkflowOutcome.Rejected) outcome).reason()).isEqualTo(Reason.POLICY_INELIGIBLE);
    }

    @Test
    void extractOrderId_normalizesToUpperCase() {
        // 2026-09-17 定案：(?i) 只管匹配、group() 原样返回——小写进精确键查找会 miss
        // （实测「ord-001退款」→ 误判「未查询到订单」）。提取点统一归一化大写。
        assertThat(AfterSaleWorkflowGraph.extractOrderIdFrom("退款 ord0001")).isEqualTo("ORD0001");
        assertThat(AfterSaleWorkflowGraph.extractOrderIdFrom("退款 ORD001")).isEqualTo("ORD001");
        assertThat(AfterSaleWorkflowGraph.extractOrderIdFrom("退款 ord-001")).isEqualTo("ORD-001");
        assertThat(AfterSaleWorkflowGraph.extractOrderIdFrom("退款 ORD-001")).isEqualTo("ORD-001");
    }
}
