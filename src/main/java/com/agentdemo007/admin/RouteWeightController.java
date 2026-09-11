package com.agentdemo007.admin;

import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import com.agentdemo007.config.ConfigWriter;
import com.agentdemo007.gateway.config.ModelConfigCenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维控制台·路由权重动态调权端点（Phase 15·T66）。
 *
 * <p>{@code POST /admin/route-weights}：动态调整模型路由权重，写回 Nacos 热加载生效。
 * <ol>
 *   <li>校验入参（modelId 非空、weight 非负，违例 → 400 BAD_REQUEST）；</li>
 *   <li>{@link ModelConfigCenter#applyWeight} 就地热生效（注册表立见新权重，无需重启）；</li>
 *   <li>{@link ConfigWriter#writeModelWeight} 写回配置中心（best-effort，②降级：写回失败不回滚内存、不抛）；</li>
 *   <li>未知模型 → 404 NOT_FOUND。</li>
 * </ol>
 *
 * <p>对外统一 {@link UnifiedResponse}（第四原则·收口）；applied/persisted 经 {@link RouteWeightResponse}
 * 强类型透出（非 Map）。鉴权在 T72 安全巡检收口（{@code /admin/*} 应受保护，此处先开放便于联调）。
 */
@RestController
@RequestMapping("/admin")
public class RouteWeightController {

    private static final Logger log = LoggerFactory.getLogger(RouteWeightController.class);

    private final ModelConfigCenter configCenter;
    private final ConfigWriter configWriter;

    public RouteWeightController(ModelConfigCenter configCenter, ConfigWriter configWriter) {
        this.configCenter = configCenter;
        this.configWriter = configWriter;
    }

    @PostMapping("/route-weights")
    public UnifiedResponse adjust(@RequestBody RouteWeightRequest request) {
        if (request == null || request.modelId() == null || request.modelId().isBlank()
                || request.weight() < 0) {
            return UnifiedResponse.error(ErrorCode.BAD_REQUEST);
        }
        boolean applied = configCenter.applyWeight(request.modelId(), request.weight());
        if (!applied) {
            return UnifiedResponse.error(ErrorCode.NOT_FOUND, "模型不存在：" + request.modelId());
        }
        boolean persisted = writeBackBestEffort(request.modelId(), request.weight());
        return UnifiedResponse.success(
                new RouteWeightResponse(request.modelId(), request.weight(), true, persisted));
    }

    /** 写回配置中心（best-effort，②降级：异常不回滚内存、不抛）。 */
    private boolean writeBackBestEffort(String modelId, int weight) {
        try {
            return configWriter.writeModelWeight(modelId, weight);
        } catch (Exception e) {
            log.warn("权重写回配置中心失败（已内存热生效，不回滚）：modelId={} weight={} reason={}",
                    modelId, weight, e.getMessage());
            return false;
        }
    }
}
