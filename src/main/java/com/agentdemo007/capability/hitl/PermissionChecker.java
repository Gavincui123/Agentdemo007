package com.agentdemo007.capability.hitl;

/**
 * 权限校验器（引擎无关 seam）。
 *
 * <p>据 sessionId + riskLevel 判定当前会话是否有权执行高风险动作。
 * §5.4.3 权限不足→工单挂起（{@link HitlDecision.Denied}）+ 审计。
 * dev 落 {@link #alwaysPermitted()}（无鉴权→放行，仅靠超时/带外决议兜底）；
 * prod 覆盖为真实鉴权（从请求上下文/用户态取权限）。
 */
@FunctionalInterface
public interface PermissionChecker {

    boolean permitted(String sessionId, String riskLevel);

    /** dev 默认：始终放行（无鉴权基础）。 */
    static PermissionChecker alwaysPermitted() {
        return (sessionId, riskLevel) -> true;
    }
}
