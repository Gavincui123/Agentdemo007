package com.agentdemo007.capability.workflow;

/**
 * 工作流审批决议 seam（高风险固定工作流·[[per-intent-dag]]·审批门委托此）。
 *
 * <p><b>语义≠ {@link com.agentdemo007.capability.hitl.HitlDecision}</b>：本 seam 是<b>执行后审批</b>
 * （submit 已跑、等人工批准/驳回退款动作），HitlDecision 是<b>执行前转人工门</b>（这单该不该转人工）。
 * 故独立 seam，不复用 HitlDecision（决策 D：执行后审批 vs 执行前转人工，语义不混）。
 *
 * <p>最小路径用 NO_OP/lambda 返回 {@link Approved}（假定批准，跑通图结构 + Retry 语义）；
 * 真 impl 走人工审批（建审批工单 + async resume，迭代后补）。
 */
public interface WorkflowApprovalDecision {

    /** 对已提交的退款请求（{@code workflowResult}）等待人工审批，返回终态。 */
    Outcome await(String workflowResult);

    /** 审批终态（sealed）：Approved 放行→END；Denied 驳回→Retry（改写重提 submit）；Timeout→END 短路。 */
    sealed interface Outcome permits Approved, Denied, Timeout {
    }

    /** 人工已批准，退款动作可完成。 */
    record Approved(String approver) implements Outcome {
    }

    /** 人工驳回，图 Retry 回 submit 改写重提（maxIterations 护栏兜底死循环）。 */
    record Denied(String reason) implements Outcome {
    }

    /** 审批超时未决议，短路话术（不自动完成退款）。 */
    record Timeout() implements Outcome {
    }
}
