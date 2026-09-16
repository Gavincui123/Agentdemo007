package com.agentdemo007.capability.workflow;

import com.agentdemo007.common.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RefundWorkflowGraph} 单测（高风险固定工作流·Slice 1-2·[[per-intent-dag]] 纠正后方向）。
 *
 * <p>固定 LangGraph 子图：submit_refund → approval_gate（条件边：approved→END / denied→retry / timeout→END）。
 * Slice 1 验 approved 路径（含终态返回）；Slice 2 验 denied→Retry 回 submit 改写重提，二次批准后到 END；
 * Slice 4 验 approval Timeout→END 返回 Timeout 终态。
 * seams 用 lambda（NO_OP-ish：submit 返回递增 id、approval 按内容驳回/批准）；真 refund 后端 / 真人工审批迭代后补。
 */
class RefundWorkflowGraphTest {

    @Test
    void invoke_submitThenApproval_approved_writesWorkflowResultAndReturnsApproved() {
        // submit 返回退款请求 id；approval 立即批准（最小路径假定批准，跑通图结构）
        RefundService refundService = ctx -> "WF-123";
        WorkflowApprovalDecision approval = workflowResult -> new WorkflowApprovalDecision.Approved("auto");
        RefundWorkflowGraph graph = new RefundWorkflowGraph(refundService, approval);

        PipelineContext context = new PipelineContext("s1", "我要退款 订单1001");
        WorkflowApprovalDecision.Outcome outcome = graph.invoke(context);

        // submit 节点写了 workflowResult；approval Approved → 图到达 END，返回 Approved 终态
        assertThat(outcome).isInstanceOf(WorkflowApprovalDecision.Approved.class);
        assertThat(context.workflowResult()).isEqualTo("WF-123");
    }

    /**
     * Slice 2：denied→Retry。approval 首次驳回 → 条件边 DENIED_BRANCH 回 submit_refund 改写重提 →
     * 二次提交 → approval 批准 → END。验 submit 跑 2 次（Retry 真触发）且 workflowResult 为最终提交 id。
     */
    @Test
    void invoke_deniedThenApproved_submitRetriesUntilApproved() {
        // submit 每次返回递增 id（WF-1, WF-2...）；approval 首次驳回→Retry，二次批准→END
        AtomicInteger submitCount = new AtomicInteger();
        RefundService refundService = ctx -> "WF-" + submitCount.incrementAndGet();
        WorkflowApprovalDecision approval = workflowResult ->
                "WF-1".equals(workflowResult)
                        ? new WorkflowApprovalDecision.Denied("首次驳回·改写重提")
                        : new WorkflowApprovalDecision.Approved("auto");
        RefundWorkflowGraph graph = new RefundWorkflowGraph(refundService, approval);

        PipelineContext context = new PipelineContext("s1", "我要退款 订单1001");
        graph.invoke(context);

        // 驳回→Retry 回 submit（第二次提交）→批准→END：submit 跑 2 次，workflowResult 为最终提交 id
        assertThat(submitCount.get()).isEqualTo(2);
        assertThat(context.workflowResult()).isEqualTo("WF-2");
    }

    /**
     * Slice 4：approval Timeout → 条件边 TIMEOUT_BRANCH 到 END，invoke 返回 Timeout 终态
     * （submit 仍执行，workflowResult 已写——退款已提交但未获批准，下游须据此短路非假装成功）。
     */
    @Test
    void invoke_timeoutApproval_returnsTimeoutOutcome() {
        RefundService refundService = ctx -> "WF-999";
        WorkflowApprovalDecision timeout = workflowResult -> new WorkflowApprovalDecision.Timeout();
        RefundWorkflowGraph graph = new RefundWorkflowGraph(refundService, timeout);

        PipelineContext context = new PipelineContext("s1", "我要退款 订单1001");
        WorkflowApprovalDecision.Outcome outcome = graph.invoke(context);

        assertThat(outcome).isInstanceOf(WorkflowApprovalDecision.Timeout.class);
        assertThat(context.workflowResult()).isEqualTo("WF-999"); // submit 仍执行（退款已提交）
    }
}
