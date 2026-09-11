package com.agentdemo007.access;

import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.response.UnifiedResponse;
import com.agentdemo007.common.trace.TraceId;
import com.agentdemo007.observability.audit.AuditEvent;
import com.agentdemo007.observability.audit.AuditEventType;
import com.agentdemo007.persistence.mq.AuditProducer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 输入安全过滤器。
 *
 * <p>检测提示注入特征，命中时直接返回系统预设话术（HTTP 200 正常对话回复）+ 审计 + 短路，
 * 不进入下游、零 LLM 调用。面向用户不暴露 4xx 技术码。
 * 注入特征库（Phase 1 基线）后续迁入 Nacos 热更新；请求体扫描待 Phase 12 {@code /chat} 落地后扩展。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InputSecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InputSecurityFilter.class);

    private static final List<String> PATTERNS = List.of(
            "ignore previous instructions",
            "ignore all previous",
            "disregard the above",
            "disregard previous",
            "reveal your instructions",
            "reveal the system prompt",
            "system prompt",
            "jailbreak",
            "<system>",
            "</system>",
            "forget your rules",
            "you are now in developer mode"
    );

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DegradationPhraseCenter phraseCenter;

    @Autowired
    private AuditProducer auditProducer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        for (String candidate : collectCandidates(request)) {
            if (matches(candidate)) {
                degrade(response, candidate);
                return; // 短路，不进下游
            }
        }
        filterChain.doFilter(request, response);
    }

    private List<String> collectCandidates(HttpServletRequest request) {
        List<String> candidates = new ArrayList<>();
        candidates.add(request.getRequestURI());
        request.getParameterMap().values().forEach(values ->
                Collections.addAll(candidates, values));
        return candidates;
    }

    private boolean matches(String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return false;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        return PATTERNS.stream().anyMatch(lower::contains);
    }

    private void degrade(HttpServletResponse response, String matched) throws IOException {
        String phrase = phraseCenter.phrase(DegradationScenario.INJECTION);
        log.warn("提示注入拦截: 特征命中 [{}]，返回话术短路", mask(matched));
        publishInjectionAudit(matched); // 预流水线审计点直投（best-effort，§5.12）
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Object data = Map.of(
                "reply", phrase,
                "degraded", true,
                "scenario", DegradationScenario.INJECTION.name());
        response.getWriter().write(objectMapper.writeValueAsString(UnifiedResponse.success(data)));
    }

    /** 预流水线审计点：接入层无 context，直接投递 INJECTION 审计事件（best-effort，不影响短路响应，§5.12）。 */
    private void publishInjectionAudit(String matched) {
        try {
            auditProducer.publish(AuditEvent.of(AuditEventType.INJECTION,
                    TraceId.current(), null, "注入命中:" + mask(matched)));
        } catch (Exception e) {
            log.warn("注入审计投递失败（不影响短路响应）：{}", e.getMessage());
        }
    }

    private String mask(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}
