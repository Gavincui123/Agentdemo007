package com.agentdemo007.gate;

import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 访问闸口端点单测（2026-09-18 部署闸门）。
 *
 * <p>{@code GET /api/gate/status}：公开探测（enabled/dailyLimit，不泄露口令）；
 * {@code POST /api/gate/login}：口令正确→返回剩余额度（不消耗），错误/未配置→401 话术。
 * Mock Servlet 取 IP，服务直构不连真 Nacos。
 */
class GateControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/gate/login");

    private GateController controller(GateRule rule) {
        return new GateController(new AccessGateService(rule, CLOCK, null, null, null));
    }

    @Test
    void status_returnsEnabledAndLimit_withoutCodeLeak() {
        UnifiedResponse resp = controller(new GateRule(true, "secret-code", 20, "r", "e")).status();

        assertThat(resp.code()).isZero();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.data();
        assertThat(data.get("enabled")).isEqualTo(true);
        assertThat(data.get("dailyLimit")).isEqualTo(20);
        assertThat(resp.toString()).doesNotContain("secret-code"); // 口令不外泄
    }

    @Test
    void login_correctCode_returnsRemaining_notConsumed() {
        GateController controller = controller(new GateRule(true, "secret-code", 5, "r", "e"));

        UnifiedResponse resp = controller.login(new GateController.LoginRequest("secret-code"), request);

        assertThat(resp.code()).isZero();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.data();
        assertThat(data.get("remaining")).isEqualTo(5); // 登录不消耗额度
    }

    @Test
    void login_wrongCode_returns401() {
        UnifiedResponse resp = controller(new GateRule(true, "secret-code", 5, "r", "e"))
                .login(new GateController.LoginRequest("nope"), request);

        assertThat(resp.code()).isEqualTo(ErrorCode.UNAUTHORIZED.code());
        assertThat(resp.message()).isEqualTo("访问口令不正确");
    }

    @Test
    void login_blankConfiguredCode_returnsConfigHint401() {
        // 闸口开启但未配置口令（Nacos 配置遗漏）→ fail-closed + 明确指引
        UnifiedResponse resp = controller(new GateRule(true, "", 5, "r", "e"))
                .login(new GateController.LoginRequest("anything"), request);

        assertThat(resp.code()).isEqualTo(ErrorCode.UNAUTHORIZED.code());
        assertThat(resp.message()).contains("未配置访问口令");
    }
}
