package com.agentdemo007.gateway.config;

import com.agentdemo007.intent.Intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 基于 {@link LlmProperties} 的真 {@link ModelConfigSource}（主备容灾·T7）。
 *
 * <p>把 {@code llm.providers}（提供方列表）翻成 {@link ModelConfigSnapshot}，收口三件事：
 * <ol>
 *   <li><b>模型注册</b>：每 provider 两个模型 {@code {id}-large}/{@code {id}-small}，带 endpoint/apiKey/provider；</li>
 *   <li><b>主备区分</b>：主 provider（列表首位）打 RouteType 标签（大=REASONING/LONG_CONTEXT/STRUCTURED，
 *       小=SIMPLE），备 provider <b>不打标签</b>——仅经 fallback 链可达，不参与 {@code byTag} 主选，
 *       避免备选被当主选误用；</li>
 *   <li><b>路由规则</b>：5 条——CHIT_CHAT→主-small[备-small 链]，
 *       REASONING/LONG_CONTEXT/STRUCTURED_EXTRACTION/OTHER→主-large[备-large 链]。</li>
 * </ol>
 *
 * <p>备链按 provider 列表顺序：{@code providers[1..]} 的 large 即大模型备链，small 即小模型备链。
 * 单 provider 退化为空备链（单模型）；空 providers 退化为空快照（不阻塞装配）。
 *
 * <p>注：{@link ModelConfigSnapshot} 的 flowControl/failover 策略留空（null）——
 * 预算关卡见 {@code FlowControlPolicy}（TokenBudgetChecker 对 null 容错），
 * 故障转移候选经 {@link RouteRule#fallbackModelIds()} 按意图取（{@code ChatLlmService.invoke} 建 FailoverPolicy），
 * 不再依赖快照级单一 FailoverPolicy。
 */
public class LlmPropertiesModelConfigSource implements ModelConfigSource {

    private static final Set<String> LARGE_TAGS = Set.of(
            RouteRule.RouteType.REASONING.name(),
            RouteRule.RouteType.LONG_CONTEXT.name(),
            RouteRule.RouteType.STRUCTURED.name());
    private static final Set<String> SMALL_TAGS = Set.of(RouteRule.RouteType.SIMPLE.name());

    private final LlmProperties props;

    public LlmPropertiesModelConfigSource(LlmProperties props) {
        this.props = props;
    }

    @Override
    public ModelConfigSnapshot load() {
        List<LlmProperties.Provider> providers = props.getProviders();
        if (providers == null || providers.isEmpty()) {
            return new ModelConfigSnapshot(List.of(), List.of(), null, null);
        }
        LlmProperties.Provider primary = providers.get(0);

        List<ModelMetadata> models = new ArrayList<>();
        List<String> largeFallback = new ArrayList<>();
        List<String> smallFallback = new ArrayList<>();

        // 主 provider：打 RouteType 标签（参与 byTag 主选）
        models.add(buildModel(primary, "large", LARGE_TAGS));
        models.add(buildModel(primary, "small", SMALL_TAGS));
        // 备 provider：不打标签（仅经 fallback 链可达），同时收集备链
        for (int i = 1; i < providers.size(); i++) {
            LlmProperties.Provider p = providers.get(i);
            models.add(buildModel(p, "large", Set.of()));
            models.add(buildModel(p, "small", Set.of()));
            largeFallback.add(modelId(p, "large"));
            smallFallback.add(modelId(p, "small"));
        }

        List<RouteRule> rules = List.of(
                new RouteRule("r-chitchat", Intent.CHIT_CHAT, RouteRule.RouteType.SIMPLE,
                        modelId(primary, "small"), smallFallback),
                new RouteRule("r-reasoning", Intent.REASONING, RouteRule.RouteType.REASONING,
                        modelId(primary, "large"), largeFallback),
                new RouteRule("r-long-context", Intent.LONG_CONTEXT, RouteRule.RouteType.LONG_CONTEXT,
                        modelId(primary, "large"), largeFallback),
                new RouteRule("r-structured", Intent.STRUCTURED_EXTRACTION, RouteRule.RouteType.STRUCTURED,
                        modelId(primary, "large"), largeFallback),
                new RouteRule("r-other", Intent.OTHER, RouteRule.RouteType.REASONING,
                        modelId(primary, "large"), largeFallback));

        return new ModelConfigSnapshot(models, rules, null, null);
    }

    private static String modelId(LlmProperties.Provider p, String role) {
        return p.getId() + "-" + role;
    }

    private static ModelMetadata buildModel(LlmProperties.Provider p, String role, Set<String> tags) {
        return ModelMetadata.builder(modelId(p, role))
                .provider(p.getId())
                .endpoint(p.getBaseUrl())
                .apiKey(p.getApiKey())
                .tags(tags)
                .build();
    }
}
