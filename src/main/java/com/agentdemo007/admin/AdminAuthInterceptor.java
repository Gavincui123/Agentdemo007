package com.agentdemo007.admin;

import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 管理台鉴权拦截器（Phase 19·T103）——{@code /admin/**} 与 {@code /api/obs/**} 边界话术短路。
 *
 * <p>取 {@code X-Admin-Token} 请求头 → {@link AdminAuthenticator#authenticate}；未授权（null/错 token）→
 * {@link UnifiedResponse#error(ErrorCode) UNAUTHORIZED}（code=401，data=null，不外泄模型/工单/指标），
 * <b>同形 HTTP 200</b> + code 体内表达（①话术短路：不抛 5xx、不进下游控制器，与
 * {@link com.agentdemo007.eval.EvalController} 的 401 同形；与 {@code InputSecurityFilter} 的
 * 写体同构：setStatus 200 + application/json + UTF-8）。
 *
 * <p>鉴权 seam 而非散落各控制器：4 个管理台端点（AdminModelController/AdminHitlController/
 * SessionHistoryController/ObservabilityController）零改动、各自单测不受影响（直构绕过拦截器）。
 * ④统一收口：管理台鉴权唯一入口，prod 覆盖 {@link AdminAuthenticator} 一处 bean 即生效。
 *
 * <p>{@code traceId} 在本拦截器调用时已由 {@code TraceFilter}（HIGHEST_PRECEDENCE）写入 MDC，
 * 故 {@link UnifiedResponse#error} 经 {@link com.agentdemo007.common.trace.TraceId#current()} 收口解析无误。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthInterceptor.class);

    /** 鉴权令牌请求头（对齐前端 auth.ts 的 X-Admin-Token）。 */
    public static final String HEADER = "X-Admin-Token";

    private final AdminAuthenticator authenticator;
    private final ObjectMapper objectMapper;

    public AdminAuthInterceptor(AdminAuthenticator authenticator, ObjectMapper objectMapper) {
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String token = request.getHeader(HEADER);
        if (!authenticator.authenticate(token)) {
            log.warn("管理台鉴权失败：X-Admin-Token 缺失或不符（path={}）", request.getRequestURI());
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(UnifiedResponse.error(ErrorCode.UNAUTHORIZED)));
            return false; // 短路，不进下游
        }
        return true;
    }
}
