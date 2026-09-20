package com.agentdemo007.capability.workflow;

/**
 * 工作流人工审批提交 seam（2026-09-20 原则翻转·用户裁决：审批是事件，Agent 最小权限）。
 *
 * <p><b>提交制语义</b>：本 seam 只做一件事——把高风险售后申请建成 PENDING 人工工单并<b>立即返回</b>；
 * 人工批准/驳回是管理台对工单的状态变更（事件），<b>不存在请求内等待</b>（等待窗口/桥唤醒机制已退役，
 * 超时不可能影响决议）。业务执行属业务系统内部运作（批准事件由 {@link AfterSaleBusinessExecutor}
 * mock 承接留痕）；Agent 侧对结果的感知只有一条路——经工单状态查询工具读工单状态。
 *
 * <p><b>与 {@link com.agentdemo007.capability.hitl.HitlDecision} 的语义分工</b>：本 seam 是
 * 「提交人工审批」（建单即返回）；HitlDecision 是「执行前转人工门」。二者均不代替业务系统执行。
 *
 * <p>同业务键（{@code wfa:{action}:{orderId}}）复用语义：PENDING 复用同单（不重复建单）、
 * APPROVED 视为已受理（绝不重建单防重复业务动作）、REJECTED/TIMEOUT 允许重建新单
 * （驳回是那张工单的事件，不封禁客户再次申请）。
 */
public interface WorkflowApprovalSubmitter {

    /**
     * 审批提交上下文（建单时富集进工单展示：管理员一屏可审"审什么、凭什么"）。
     *
     * @param sessionId        会话标识
     * @param action           动作（REFUND / RETURN，对齐业务门 {@code wfa:{action}:{orderId}} 锚点解析）
     * @param orderId          订单号
     * @param orderSummary     订单实时事实摘要（人类可读）
     * @param policyConclusion 政策结论（query_policy 召回片段；兜底话术已过滤为 null）
     * @param eligibilityNote  Agent 资格判定摘要（decision：basis；UNCERTAIN 时管理员须重点复核）
     * @param userQuery        用户申请原词（改写后查询）
     */
    record ApprovalRequest(String sessionId, String action, String orderId,
                           String orderSummary, String policyConclusion, String eligibilityNote,
                           String userQuery) {
    }

    /**
     * 提交人工审批并立即返回（不阻塞、无等待窗口）。
     *
     * @param ticketId        建单/复用/已批的工单 id
     * @param alreadyApproved true=同业务键工单此前已批准（未建新单，防重复业务动作）
     */
    Outcome submit(ApprovalRequest request);

    /** 提交结果：工单已受理（新建/复用）或同键此前已批准。 */
    record Outcome(String ticketId, boolean alreadyApproved) {
    }
}
