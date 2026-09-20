package com.agentdemo007.gate;

import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Set;

/**
 * 访问闸口过滤器（2026-09-18 部署闸门）——简历展示站点的对话入口限流。
 *
 * <p>只闸对话入口 {@code /chat} 与 {@code /chat/stream}（/admin、/api/obs、/eval 各有自有鉴权；
 * /health 公开）。链序：TraceFilter(HIGHEST) → <b>闸口(+5)</b> → InputSecurityFilter(+10) →
 * ValidationFilter(+20)——traceId 先行，闸口拒绝体可追溯。
 *
 * <p>三道判定（规则实时读 {@link AccessGateService#current()}，Nacos 热更新即改即生效）：
 * <ol>
 *   <li>开关关闭→全开放直通；</li>
 *   <li>{@code X-Access-Code} 逐字比对（常量时间），缺失/不符→ {@code code=401} + 可配置话术；</li>
 *   <li>按 IP 每自然日（Asia/Shanghai）原子计数，超 {@code dailyLimit} → {@code code=429} + 可配置话术。</li>
 * </ol>
 *
 * <p>①话术短路同形：始终 HTTP 200 + application/json + UTF-8 写 {@link UnifiedResponse}
 * （与 AdminAuthInterceptor/InputSecurityFilter 同构，不暴露 4xx 技术码；SSE 请求被闸时前端
 * 按 Content-Type 识别 JSON 拒绝体，透传话术且不重试）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class AccessGateFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessGateFilter.class);

    /** 闸口令牌请求头（对齐前端 gate.ts 的 X-Access-Code）。 */
    public static final String HEADER = "X-Access-Code";

    /** 只闸对话入口（精确路径）。 */
    static final Set<String> GATED_PATHS = Set.of("/chat", "/chat/stream");

    private final AccessGateService gateService;
    private final ObjectMapper objectMapper;

    public AccessGateFilter(AccessGateService gateService, ObjectMapper objectMapper) {
        this.gateService = gateService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!GATED_PATHS.contains(request.getServletPath())) {
            filterChain.doFilter(request, response);
            return;
        }
        GateRule rule = gateService.current();
        if (!rule.active()) {
            filterChain.doFilter(request, response);
            return;
        }
        // localhost 豁免（2026-09-18 用户裁决）：统一旋钮只对外部 IP 生效——本机联调/运维脚本
        // 不消耗额度、不需口令。判定口径见 isLoopbackClient（直连看 TCP 对端；经代理看
        // nginx 覆写的 X-Real-IP，伪造头不生效）。
        if (AccessGateService.isLoopbackClient(request)) {
            log.debug("闸口 localhost 豁免直通：path={}", request.getServletPath());
            filterChain.doFilter(request, response);
            return;
        }
        String given = request.getHeader(HEADER);
        if (!AccessGateService.codeMatches(given, rule.accessCode())) {
            log.info("闸口拒绝（口令缺失或不符）：path={} ip={}", request.getServletPath(),
                    AccessGateService.clientIp(request));
            reject(response, ErrorCode.UNAUTHORIZED, rule.requiredMessage());
            return;
        }
        String ip = AccessGateService.clientIp(request);
        if (!gateService.tryAcquire(ip, rule.dailyLimit())) {
            log.info("闸口拒绝（当日额度用尽）：ip={} dailyLimit={}", ip, rule.dailyLimit());
            reject(response, ErrorCode.RATE_LIMITED, rule.exhaustedMessage());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(UnifiedResponse.error(code, message)));
    }
}
