package com.agentdemo007.gate;

import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 访问闸口端点（2026-09-18 部署闸门·前端登录页配套）。
 *
 * <p>{@code GET /api/gate/status}：公开探测（不泄露口令）——前端路由守卫据此决定是否拦到登录页；
 * {@code enabled=false} 时前端全开放，后端过滤器同样直通。
 *
 * <p>{@code POST /api/gate/login}：口令校验（不消耗当日额度），通过→返回今日剩余轮次；
 * 不符→ {@code code=401} + 话术。鉴权本身由 {@link AccessGateFilter} 在对话入口强制，
 * 本端点只做登录页的即时反馈（录错口令当场知道，而非第一轮对话才失败）。
 */
@RestController
@RequestMapping("/api/gate")
public class GateController {

    private final AccessGateService gateService;

    public GateController(AccessGateService gateService) {
        this.gateService = gateService;
    }

    @GetMapping("/status")
    public UnifiedResponse status() {
        GateRule rule = gateService.current();
        return UnifiedResponse.success(Map.of(
                "enabled", rule.active(),
                "dailyLimit", rule.dailyLimit()));
    }

    @PostMapping("/login")
    public UnifiedResponse login(@RequestBody LoginRequest body, HttpServletRequest request) {
        GateRule rule = gateService.current();
        if (!AccessGateService.codeMatches(body.code(), rule.accessCode())) {
            return UnifiedResponse.error(ErrorCode.UNAUTHORIZED,
                    rule.accessCode() == null || rule.accessCode().isBlank()
                            ? "闸口未配置访问口令，请检查 Nacos 配置"
                            : "访问口令不正确");
        }
        return UnifiedResponse.success(Map.of(
                "remaining", gateService.remaining(AccessGateService.clientIp(request), rule.dailyLimit())));
    }

    /** 登录请求体 {@code {"code":"…"}}。 */
    public record LoginRequest(String code) {
    }
}
