package com.agentdemo007.admin;

import com.agentdemo007.common.response.UnifiedResponse;
import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.config.ModelMetadata;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理台·模型配置查询端点（Phase 19·T96）。
 *
 * <p>{@code GET /admin/models}：只读 {@link ModelConfigCenter#registry()} 全量快照，经
 * {@link ModelSummary} 强类型对外收口——<b>剔除 {@code apiKey}</b>（密钥不外泄，安全 + ④收口）。
 * 权重为热生效后的当前值（与 {@code RouteWeightController} 的 {@code POST /admin/route-weights}
 * 联动：调权后此处立见新值，无需重启）。
 *
 * <p>对外统一 {@link UnifiedResponse}（HTTP 200 + code 体内表达）；鉴权在 T103 收口（{@code /admin/*}
 * 应受保护，此处先开放便于联调，与 {@code RouteWeightController} 同期）。
 */
@RestController
@RequestMapping("/admin")
public class AdminModelController {

    private final ModelConfigCenter configCenter;

    public AdminModelController(ModelConfigCenter configCenter) {
        this.configCenter = configCenter;
    }

    @GetMapping("/models")
    public UnifiedResponse models() {
        List<ModelSummary> summaries = configCenter.registry().all().stream()
                .map(AdminModelController::toSummary)
                .toList();
        return UnifiedResponse.success(summaries);
    }

    private static ModelSummary toSummary(ModelMetadata m) {
        return new ModelSummary(m.id(), m.name(), m.provider(), m.endpoint(),
                m.weight(), m.status().name(), m.isEnabled(),
                m.tags(), m.maxTokens(), m.costPer1KTokens());
    }
}
