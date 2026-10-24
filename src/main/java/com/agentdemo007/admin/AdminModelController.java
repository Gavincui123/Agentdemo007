package com.agentdemo007.admin;

import com.agentdemo007.common.response.UnifiedResponse;
import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.config.ModelMetadata;
import com.agentdemo007.intent.LingDtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 管理台·模型配置查询端点（Phase 19·T96）。
 *
 * <p>{@code GET /admin/models}：只读 {@link ModelConfigCenter#registry()} 全量快照，经
 * {@link ModelSummary} 强类型对外收口——<b>剔除 {@code apiKey}</b>（密钥不外泄，安全 + ④收口）。
 * 权重为热生效后的当前值（与 {@code RouteWeightController} 的 {@code POST /admin/route-weights}
 * 联动：调权后此处立见新值，无需重启）。
 *
 * <p><b>意图决策通道补录</b>（2026-10-24）：{@code lingdt.enabled=true} 时在末尾追加一条
 * LingDT-Decision 合成条目——它不进网关注册表（专有决策 API 非 OpenAI 兼容，进了会被
 * {@code ModelRouter} 当生成模型路由选中），但模型权重页应能看到这条新通道的存在：
 * weight=0（不参与权重路由）、tags 标注用途与回退关系。未启用时不追加，页面与接入前一致；
 * 注册表已有同名 id 时跳过补录（防重复条目/调权歧义，2026-10-24 review 收口）。
 */
@RestController
@RequestMapping("/admin")
public class AdminModelController {

    private static final Logger log = LoggerFactory.getLogger(AdminModelController.class);

    private final ModelConfigCenter configCenter;
    private final LingDtProperties lingDt;

    public AdminModelController(ModelConfigCenter configCenter, LingDtProperties lingDt) {
        this.configCenter = configCenter;
        this.lingDt = lingDt;
    }

    @GetMapping("/models")
    public UnifiedResponse models() {
        List<ModelSummary> summaries = new ArrayList<>(configCenter.registry().all().stream()
                .map(AdminModelController::toSummary)
                .toList());
        if (lingDt.isEnabled() && !registryHasDecisionChannel()) {
            summaries.add(decisionChannelSummary());
        }
        return UnifiedResponse.success(summaries);
    }

    /** 注册表已存在与决策通道同 id 的真实模型（撞名）→ 跳过合成条目，避免重复行与调权归属歧义。 */
    private boolean registryHasDecisionChannel() {
        String id = lingDt.getModel();
        boolean collision = id != null && configCenter.registry().all().stream()
                .anyMatch(m -> id.equals(m.id()));
        if (collision) {
            log.warn("注册表已存在同 id 模型，跳过 LingDT 合成条目补录：id={}", id);
        }
        return collision;
    }

    private static ModelSummary toSummary(ModelMetadata m) {
        return new ModelSummary(m.id(), m.name(), m.provider(), m.endpoint(),
                m.weight(), m.status().name(), m.isEnabled(),
                m.tags(), m.maxTokens(), m.costPer1KTokens());
    }

    /** 意图决策通道的可见性投影（合成条目，非注册表成员）：weight=0 + 标签注明用途与回退。 */
    private ModelSummary decisionChannelSummary() {
        return new ModelSummary(lingDt.getModel(), "LingDT-Decision", "antdigital-dtmaas",
                lingDt.getEndpoint(), 0, "ENABLED", true,
                Set.of("意图识别·决策专用", "不参与权重路由", "不可用回退通用小模型"),
                0, 0.0);
    }
}
