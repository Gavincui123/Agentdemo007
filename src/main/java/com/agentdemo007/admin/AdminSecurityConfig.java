package com.agentdemo007.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 管理台鉴权装配（Phase 19·T103）。
 *
 * <p>镜像 {@code EvalConfig#evalAuthenticator} 的 @ConditionalOnMissingBean seam：dev 默认逐字比对
 * 配置 token（{@code agentdemo.admin.token}，缺省 {@code dev-admin-token}，机制强制鉴权——
 * 无 token 即拒，满足验收"未授权不可访问 {@code /admin/*} {@code /api/obs/*}"）；
 * prod 用真实 SSO/OIDC bean 覆盖 {@link AdminAuthenticator} 一处即全链路生效。
 */
@Configuration
public class AdminSecurityConfig {

    @Bean
    @ConditionalOnMissingBean(AdminAuthenticator.class)
    AdminAuthenticator adminAuthenticator(
            @Value("${agentdemo.admin.token:dev-admin-token}") String expectedToken) {
        return token -> expectedToken != null && expectedToken.equals(token);
    }
}
