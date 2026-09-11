package com.agentdemo007.gateway.config;

import java.util.List;

/**
 * 模型配置快照（一次加载的不可变配置切面）。
 *
 * <p>由 {@link ModelConfigSource} 产出，含模型元数据表、路由规则、全局流控、全局容灾。
 * 收口：热加载以"整表替换"语义落地到 {@link com.agentdemo007.gateway.registry.ModelRegistry}，
 * 避免增量补丁造成的状态漂移。
 */
public record ModelConfigSnapshot(
        List<ModelMetadata> models,
        List<RouteRule> routeRules,
        FlowControlPolicy flowControlPolicy,
        FailoverPolicy failoverPolicy) {
}
