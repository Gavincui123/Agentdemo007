package com.agentdemo007.admin;

import com.agentdemo007.common.response.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理台鉴权拦截器单测（Phase 19·T103）。
 *
 * <p>直接构造 {@link AdminAuthInterceptor}（直构非 MockMvc，与同包控制器单测同构），
 * 断言 ①话术短路：未授权→HTTP 200 + UnifiedResponse(code=401) 体内表达（不 5xx、不外泄数据）；
 * 授权→放行不写体。
 */
class AdminAuthInterceptorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingToken_shortCircuitsToUnauthorizedUnifiedResponse() throws Exception {
        AdminAuthenticator authenticator = token -> false; // 恒拒
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor(authenticator, mapper);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Admin-Token")).thenReturn(null);
        when(req.getRequestURI()).thenReturn("/admin/models");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        StringWriter sw = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(sw));

        boolean proceed = interceptor.preHandle(req, resp, new Object());

        assertThat(proceed).isFalse(); // 短路，不进下游控制器
        // 同形 HTTP 200 + code 体内表达（不 5xx，①话术短路）
        verify(resp).setStatus(200);
        verify(resp).setContentType("application/json");
        verify(resp).setCharacterEncoding("UTF-8");
        String body = sw.toString();
        assertThat(body).contains("\"code\":401");
        assertThat(body).contains("\"message\":\"" + ErrorCode.UNAUTHORIZED.message() + "\"");
        assertThat(body).contains("\"traceId\""); // 话术短路仍带链路
        assertThat(body).contains("\"data\":null"); // 不外泄数据
    }

    @Test
    void wrongToken_shortCircuits() throws Exception {
        AdminAuthenticator authenticator = "dev-admin-token"::equals;
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor(authenticator, mapper);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Admin-Token")).thenReturn("wrong");
        when(req.getRequestURI()).thenReturn("/api/obs/summary");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        StringWriter sw = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(sw));

        boolean proceed = interceptor.preHandle(req, resp, new Object());

        assertThat(proceed).isFalse();
        assertThat(sw.toString()).contains("\"code\":401");
    }

    @Test
    void validToken_passesThroughWithoutWritingBody() throws Exception {
        AdminAuthenticator authenticator = "dev-admin-token"::equals;
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor(authenticator, mapper);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Admin-Token")).thenReturn("dev-admin-token");
        HttpServletResponse resp = mock(HttpServletResponse.class);

        boolean proceed = interceptor.preHandle(req, resp, new Object());

        assertThat(proceed).isTrue();
        verify(resp, never()).getWriter();
        verify(resp, never()).setStatus(200);
    }
}
