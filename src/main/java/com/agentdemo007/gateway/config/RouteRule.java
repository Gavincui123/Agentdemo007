package com.agentdemo007.gateway.config;

import com.agentdemo007.intent.Intent;

import java.util.List;

/**
 * 意图→模型路由规则（第六层·路由选择输入）。
 *
 * <p>将识别出的 {@link Intent} 映射到 {@link RouteType} 再落到目标模型标识，
 * 是 {@code ModelSelector} 的策略输入之一。
 *
 * <p>主备容灾（2026-09）：{@link #fallbackModelIds()} 携带该意图的备选模型链——
 * 主模型（{@link #targetModelId()}）熔断/失败时，{@code FailoverExecutor} 按此序列切备。
 * 主备链与路由同处收口：{@code ChatLlmService} 已手持 rule，直接取主+备建 {@link FailoverPolicy}，
 * 无需改 {@link ModelConfigSnapshot} 结构。4 参构造（无备）向后兼容：备链默认空（单模型退化）。
 */
public class RouteRule {

    public enum RouteType { SIMPLE, REASONING, LONG_CONTEXT, STRUCTURED }

    private final String id;
    private final Intent intent;
    private final RouteType routeType;
    private final String targetModelId;
    private final List<String> fallbackModelIds;

    /** 向后兼容构造：无备链（单模型，dev/既有调用方）。 */
    public RouteRule(String id, Intent intent, RouteType routeType, String targetModelId) {
        this(id, intent, routeType, targetModelId, List.of());
    }

    /** 主备构造：主模型 + 备选链（{@code FailoverExecutor} 按序切备）。 */
    public RouteRule(String id, Intent intent, RouteType routeType, String targetModelId,
                     List<String> fallbackModelIds) {
        this.id = id;
        this.intent = intent;
        this.routeType = routeType;
        this.targetModelId = targetModelId;
        this.fallbackModelIds = fallbackModelIds == null ? List.of() : List.copyOf(fallbackModelIds);
    }

    public String id() { return id; }
    public Intent intent() { return intent; }
    public RouteType routeType() { return routeType; }
    public String targetModelId() { return targetModelId; }

    /** 备选模型链（主失败/熔断时按序切备；空=无备，单模型退化）。 */
    public List<String> fallbackModelIds() { return fallbackModelIds; }
}
