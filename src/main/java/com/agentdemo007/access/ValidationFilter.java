package com.agentdemo007.access;

import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.response.UnifiedResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * 基础数据校验过滤器。
 *
 * <p>Phase 1 落地「消息长度上限」：请求体超过 {@code app.max-body-size} 阈值时，
 * 返回系统预设话术（HTTP 200 + degraded 标记）+ 审计 + 短路，不进入下游。
 *
 * <p>请求体非空、sessionId 格式等 {@code /chat} 专用校验待 Phase 12 落地时随端点补齐。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ValidationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ValidationFilter.class);

    @Value("${app.max-body-size:1048576}")
    private long maxBodySize;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DegradationPhraseCenter phraseCenter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBodySize) {
            degrade(response, contentLength);
            return; // 短路，不进下游
        }
        filterChain.doFilter(request, response);
    }

    private void degrade(HttpServletResponse response, long contentLength) throws IOException {
        String phrase = phraseCenter.phrase(DegradationScenario.PAYLOAD_TOO_LARGE);
        log.warn("请求体过大: {} 字节 > 阈值 {}，返回话术短路", contentLength, maxBodySize); // 审计
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Object data = Map.of(
                "reply", phrase,
                "degraded", true,
                "scenario", DegradationScenario.PAYLOAD_TOO_LARGE.name());
        response.getWriter().write(objectMapper.writeValueAsString(UnifiedResponse.success(data)));
    }
}
