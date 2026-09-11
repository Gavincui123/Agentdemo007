package com.agentdemo007.admin;

/**
 * 管理台鉴权 seam（Phase 19·T103）。
 *
 * <p>镜像 {@code EvalAuthenticator}（T70）的 seam 模式：dev 默认逐字比对配置 token
 * （{@code agentdemo.admin.token}，缺省 {@code dev-admin-token}），prod 用真实 SSO/OIDC bean 覆盖。
 * 由 {@link AdminAuthInterceptor} 在 {@code /admin/**} 与 {@code /api/obs/**} 边界话术短路调用，
 * 未授权→{@link com.agentdemo007.common.response.ErrorCode#UNAUTHORIZED}（code=401，HTTP 200 体内表达，①②）。
 */
@FunctionalInterface
public interface AdminAuthenticator {

    /** 校验令牌；{@code null}/空/不符→true，否则 false。 */
    boolean authenticate(String token);
}
