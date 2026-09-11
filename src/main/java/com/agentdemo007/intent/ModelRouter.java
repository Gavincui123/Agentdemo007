package com.agentdemo007.intent;

import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.config.ModelMetadata;
import com.agentdemo007.gateway.config.RouteRule;
import com.agentdemo007.gateway.exception.ModelSelectionException;
import com.agentdemo007.gateway.registry.ModelRegistry;
import com.agentdemo007.gateway.selector.ModelSelector;
import com.agentdemo007.gateway.selector.SelectionCriteria;
import com.agentdemo007.observability.AgentMetrics;

import java.util.List;
import java.util.Optional;

/**
 * 模型路由（第三层·对接 {@link ModelSelector} 选模型，§5.3.3/§5.3.4）。
 *
 * <p>选择逻辑：
 * <ol>
 *   <li>查配置中心 {@link ModelConfigCenter#routeFor}：若路由规则有 {@code targetModelId} → 直接用规则目标；</li>
 *   <li>否则在注册表中按 {@link RouteRule.RouteType} 标签筛启用候选，经 {@link ModelSelector} 选一个；</li>
 *   <li>无候选 → {@link ModelSelectionException}（由 {@code RouteDispatchStep} 收口为 MODEL_DOWN 话术短路）。</li>
 * </ol>
 *
 * <p>对外只产出模型标识（不暴露候选集/选择过程，§5.3.4 对外仅返回意图枚举与选定模型）。
 *
 * <p>Phase 15 可观测：每次成功路由经 {@link AgentMetrics#recordRoute} 记录路由类型 + 选定模型计数。
 */
public class ModelRouter {

    private final ModelConfigCenter center;
    private final ModelSelector selector;
    private final AgentMetrics metrics;

    public ModelRouter(ModelConfigCenter center, ModelSelector selector) {
        this(center, selector, AgentMetrics.NO_OP);
    }

    public ModelRouter(ModelConfigCenter center, ModelSelector selector, AgentMetrics metrics) {
        this.center = center;
        this.selector = selector;
        this.metrics = metrics;
    }

    /**
     * 按意图 + 路由类型选模型。
     *
     * @throws ModelSelectionException 无可用候选时
     */
    public String route(Intent intent, RouteRule.RouteType routeType) {
        Optional<RouteRule> rule = center.routeFor(intent);
        if (rule.isPresent() && rule.get().targetModelId() != null
                && !rule.get().targetModelId().isBlank()) {
            String target = rule.get().targetModelId();
            metrics.recordRoute(routeType, target);
            return target;
        }
        ModelRegistry registry = center.registry();
        String tag = routeType.name();
        List<ModelMetadata> candidates = rule.isPresent()
                ? registry.byTag(tag)
                : (registry.byTag(tag).isEmpty() ? registry.enabled() : registry.byTag(tag));
        if (candidates.isEmpty()) {
            throw new ModelSelectionException("无可用模型：intent=" + intent + " route=" + routeType);
        }
        String selected = selector.select(candidates, SelectionCriteria.byTag(tag)).id();
        metrics.recordRoute(routeType, selected);
        return selected;
    }
}
