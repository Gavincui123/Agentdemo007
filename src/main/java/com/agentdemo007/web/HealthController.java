package com.agentdemo007.web;

import com.agentdemo007.common.response.UnifiedResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查与服务信息端点，统一返回 {@link UnifiedResponse}。
 */
@RestController
public class HealthController {

    @Value("${spring.application.name:Agentdemo007}")
    private String applicationName;

    @Value("${app.version:0.0.1-SNAPSHOT}")
    private String version;

    @GetMapping("/health")
    public UnifiedResponse health() {
        return UnifiedResponse.success(Map.of(
                "status", "UP",
                "service", applicationName
        ));
    }

    @GetMapping("/info")
    public UnifiedResponse info() {
        return UnifiedResponse.success(Map.of(
                "name", applicationName,
                "version", version
        ));
    }
}
