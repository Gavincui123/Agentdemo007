package com.agentdemo007.capability.hitl;

/**
 * HITL 确认请求（第四层·能力与执行层内部强类型，不外泄到步骤间）。
 *
 * <p>由 {@link HitlHandler} 在判定需要人工确认后构建，作为 {@link HumanTicketService#createTicket}
 * 的输入。{@code riskLevel} 驱动 {@code PermissionChecker} 权限判定（§5.4.3 权限不足→工单挂起）。
 *
 * @param sessionId 会话标识
 * @param query     触发 HITL 的用户问题
 * @param reason    触发原因（审计/工单展示用）
 * @param riskLevel 风险等级（RISK_LOW / RISK_MEDIUM / RISK_HIGH）
 */
public record HitlRequest(String sessionId, String query, String reason, String riskLevel) {

    public static final String RISK_LOW = "LOW";
    public static final String RISK_MEDIUM = "MEDIUM";
    public static final String RISK_HIGH = "HIGH";
}
