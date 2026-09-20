package com.agentdemo007.gate;

import com.agentdemo007.common.response.ErrorCode;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 访问闸口过滤器单测（2026-09-18 部署闸门）——判定矩阵：
 * 非闸路径/开关关闭→直通；口令缺失/不符→401 话术；额度内→放行计数；超限→429 话术；
 * IP 取 X-Forwarded-For/X-Real-IP/remoteAddr。Mock Servlet 直驱，不连真 Nacos/流水线。
 */
class AccessGateFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final AtomicInteger chainInvoked = new AtomicInteger();

    private AccessGateFilter filter(GateRule rule) {
        return new AccessGateFilter(new AccessGateService(rule, CLOCK, null, null, null), MAPPER);
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.setServletPath(path);
        r.setRemoteAddr("9.9.9.9");
        return r;
    }

    private void doFilter(AccessGateFilter filter, MockHttpServletRequest req) throws Exception {
        filter.doFilter(req, new MockHttpServletResponse(),
                (FilterChain) (request, response) -> chainInvoked.incrementAndGet());
    }

    private Map<String, Object> bodyOf(MockHttpServletResponse res) throws Exception {
        assertThat(res.getStatus()).isEqualTo(200); // ①话术短路：HTTP 200 + 体内 code
        return MAPPER.readValue(res.getContentAsString(), new TypeReference<>() {
        });
    }

    @Test
    void nonGatedPath_passesThrough_evenWhenEnabled() throws Exception {
        AccessGateFilter filter = filter(new GateRule(true, "c", 20, "r", "e"));

        doFilter(filter, request("/admin/models"));
        doFilter(filter, request("/api/obs/summary"));

        assertThat(chainInvoked.get()).isEqualTo(2);
    }

    @Test
    void gateDisabled_passesThrough() throws Exception {
        AccessGateFilter filter = filter(new GateRule(false, null, 20, "r", "e"));

        doFilter(filter, request("/chat"));

        assertThat(chainInvoked.get()).isEqualTo(1);
    }

    @Test
    void missingCode_rejected401WithRequiredMessage_chainNotInvoked() throws Exception {
        AccessGateFilter filter = filter(new GateRule(true, "secret", 20, "请先输入访问口令", "e"));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(request("/chat"), res, (request, response) -> chainInvoked.incrementAndGet());

        assertThat(chainInvoked.get()).isZero();
        Map<String, Object> body = bodyOf(res);
        assertThat(((Number) body.get("code")).intValue()).isEqualTo(ErrorCode.UNAUTHORIZED.code());
        assertThat(body.get("message")).isEqualTo("请先输入访问口令");
    }

    @Test
    void wrongCode_rejected401() throws Exception {
        AccessGateFilter filter = filter(new GateRule(true, "secret", 20, "r", "e"));

        MockHttpServletRequest req = request("/chat");
        req.addHeader(AccessGateFilter.HEADER, "wrong");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (request, response) -> chainInvoked.incrementAndGet());

        Map<String, Object> body = bodyOf(res);
        assertThat(((Number) body.get("code")).intValue()).isEqualTo(ErrorCode.UNAUTHORIZED.code());
        assertThat(chainInvoked.get()).isZero();
    }

    @Test
    void correctCode_withinLimit_passesAndCounts_overLimitRejectedWithExhaustedMessage() throws Exception {
        AccessGateFilter filter = filter(new GateRule(true, "secret", 2, "r", "今日已用完"));

        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = request("/chat");
            req.addHeader(AccessGateFilter.HEADER, "secret");
            doFilter(filter, req);
        }
        assertThat(chainInvoked.get()).isEqualTo(2);

        MockHttpServletRequest req = request("/chat");
        req.addHeader(AccessGateFilter.HEADER, "secret");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (request, response) -> chainInvoked.incrementAndGet());

        Map<String, Object> body = bodyOf(res);
        assertThat(((Number) body.get("code")).intValue()).isEqualTo(ErrorCode.RATE_LIMITED.code());
        assertThat(body.get("message")).isEqualTo("今日已用完");
    }

    @Test
    void clientIp_prefersRealIp_thenForwardedFor_thenRemote() {
        // 2026-09-18 顺序修正：X-Real-IP（nginx 覆写 $remote_addr，伪造不生效）优先于 XFF
        MockHttpServletRequest req = request("/chat");
        req.addHeader("X-Forwarded-For", "1.1.1.1, 10.0.0.1");
        assertThat(AccessGateService.clientIp(req)).isEqualTo("1.1.1.1");

        MockHttpServletRequest req2 = request("/chat");
        req2.addHeader("X-Real-IP", "2.2.2.2");
        assertThat(AccessGateService.clientIp(req2)).isEqualTo("2.2.2.2");

        MockHttpServletRequest both = request("/chat");
        both.addHeader("X-Real-IP", "2.2.2.2");
        both.addHeader("X-Forwarded-For", "1.1.1.1, 10.0.0.1");
        assertThat(AccessGateService.clientIp(both)).isEqualTo("2.2.2.2"); // 双头：X-Real-IP 胜

        assertThat(AccessGateService.clientIp(request("/chat"))).isEqualTo("9.9.9.9");
    }

    @Test
    void loopbackBypass_skipsCodeAndQuota() throws Exception {
        // 统一旋钮 localhost 豁免：本机直连不经口令、不耗额度（2026-09-18 用户裁决）
        AccessGateFilter filter = filter(new GateRule(true, "secret", 1, "r", "e"));

        MockHttpServletRequest local = request("/chat"); // 无代理头 + remoteAddr 回环
        local.setRemoteAddr("127.0.0.1");
        doFilter(filter, local);
        doFilter(filter, local);
        doFilter(filter, local);
        assertThat(chainInvoked.get()).isEqualTo(3); // 全直通，额度不耗尽
    }

    @Test
    void spoofedForwardedFor_cannotBypassRealIpQuotaKey() throws Exception {
        // 防刷：X-Real-IP 为准，伪造 XFF 换不来新鲜额度
        AccessGateFilter filter = filter(new GateRule(true, "secret", 1, "r", "今日已用完"));

        MockHttpServletRequest first = request("/chat");
        first.addHeader(AccessGateFilter.HEADER, "secret");
        first.addHeader("X-Real-IP", "5.5.5.5");
        first.addHeader("X-Forwarded-For", "7.7.7.1");
        doFilter(filter, first);
        assertThat(chainInvoked.get()).isEqualTo(1);

        MockHttpServletRequest second = request("/chat"); // 同 X-Real-IP、换伪造 XFF
        second.addHeader(AccessGateFilter.HEADER, "secret");
        second.addHeader("X-Real-IP", "5.5.5.5");
        second.addHeader("X-Forwarded-For", "7.7.7.2");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(second, res, (request, response) -> chainInvoked.incrementAndGet());

        Map<String, Object> body = bodyOf(res);
        assertThat(((Number) body.get("code")).intValue()).isEqualTo(ErrorCode.RATE_LIMITED.code());
        assertThat(chainInvoked.get()).isEqualTo(1); // 额度按 5.5.5.5 计，未绕过
    }
}
