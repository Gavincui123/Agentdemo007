package com.agentdemo007.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 管理台 MVC 装配（Phase 19·T103）——注册 {@link AdminAuthInterceptor} 至路径前缀。
 *
 * <p>保护范围 {@code /admin/**}（模型/HITL/会话历史/调权）与 {@code /api/obs/**}（可观测快照）。
 * 不含 {@code /eval/run}（其鉴权由 {@code EvalAuthenticator} + {@code EvalController} 体内短路，
 * 令牌不同：eval token vs admin token，故不在此收口）。{@code /chat} 对话主端点不鉴权（终端用户可达）。
 */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;

    public AdminWebConfig(AdminAuthInterceptor adminAuthInterceptor) {
        this.adminAuthInterceptor = adminAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin/**", "/api/obs/**");
    }
}
