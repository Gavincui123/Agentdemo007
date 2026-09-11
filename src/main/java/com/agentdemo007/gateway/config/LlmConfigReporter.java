package com.agentdemo007.gateway.config;

import java.util.List;
import java.util.Map;

/**
 * LLM 配置启动报告器（主备容灾·可观测）。
 *
 * <p>把 {@link LlmProperties}（raw provider 配置）+ {@link ModelConfigSnapshot}（解析后的模型/路由）
 * 渲染成<b>密钥脱敏</b>的多行文本，供 {@link ModelConfigBootstrap} 启动时 INFO 打印——
 * 让「Nacos/env 读到了什么」可见可审（否则只有「启用模型 N 个」、谁都不知道具体 provider/模型/路由/熔断参数）。
 *
 * <p>密钥脱敏：仅显尾 4 位 + 长度（类信用卡指纹），不落明文——遵守「密钥不落明文」。
 * <b>不</b> dump 原始 Nacos dataId YAML（其内可能含明文密钥，打日志会泄密）——只打结构化脱敏视图。
 *
 * <p>纯函数（无 Spring/IO），可单测：构造 LlmProperties + ModelConfigSnapshot 断言输出含 provider id、
 * base-url、脱敏标记、路由主/备、熔断参数，且<b>不含</b>原始 key 全文。
 */
public final class LlmConfigReporter {

    private LlmConfigReporter() {}

    /** 渲染启动配置报告（脱敏）。props 为 null → dev 占位源分支。 */
    public static String report(LlmProperties props, ModelConfigSnapshot snapshot) {
        int enabled = snapshot == null || snapshot.models() == null ? 0 : snapshot.models().size();
        StringBuilder sb = new StringBuilder();
        sb.append("模型配置加载完成：启用模型 ").append(enabled).append(" 个");

        if (props == null) {
            sb.append("\nllm.enabled=false/缺省 → dev 占位源（Noop 执行器；llm.enabled=true 时由 LlmConfig 装配真 provider）");
            if (snapshot != null && snapshot.models() != null) {
                sb.append("\n模型: ").append(modelIds(snapshot.models()));
            }
            return sb.toString();
        }

        // prod：raw provider 配置（脱敏）+ 熔断参数 + 思考开关状态 + 解析后的路由规则
        LlmProperties.CircuitBreaker cb = props.getCircuitBreaker();
        boolean thinkingOn = props.getThinking().isEnabled();
        sb.append("\nllm.enabled=true, 熔断(threshold=").append(cb.getFailureThreshold())
                .append(", window=").append(cb.getWindowMs()).append("ms, cooldown=")
                .append(cb.getCooldownMs()).append("ms), 思考=意图驱动(闲聊恒关; 非闲聊 thinking.enabled=")
                .append(thinkingOn ? "true=开)" : "false=关)");

        List<LlmProperties.Provider> providers = props.getProviders();
        sb.append("\nproviders:");
        if (providers == null || providers.isEmpty()) {
            sb.append(" (空——llm.enabled=true 但 providers 未配置，注册表将为空→下游收口 MODEL_DOWN)");
        } else {
            for (int i = 0; i < providers.size(); i++) {
                LlmProperties.Provider p = providers.get(i);
                sb.append("\n  [").append(i == 0 ? "主" : "备").append("] id=").append(p.getId())
                        .append(" base-url=").append(p.getBaseUrl())
                        .append(" api-key=").append(maskKey(p.getApiKey()))
                        .append(" large=").append(p.getLargeModel())
                        .append(" small=").append(p.getSmallModel());
                Map<String, Object> tp = p.getDisableThinkingParams();
                sb.append(" 关思考参数=").append(tp == null || tp.isEmpty() ? "(无)" : tp);
            }
        }

        sb.append("\n路由规则:");
        if (snapshot == null || snapshot.routeRules() == null || snapshot.routeRules().isEmpty()) {
            sb.append(" (无)");
        } else {
            for (RouteRule r : snapshot.routeRules()) {
                sb.append("\n  ").append(r.intent()).append(" → ").append(r.targetModelId());
                List<String> fb = r.fallbackModelIds();
                sb.append(fb.isEmpty() ? " [备: 无]" : " [备: " + String.join(", ", fb) + "]");
            }
        }
        return sb.toString();
    }

    private static String modelIds(List<ModelMetadata> models) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < models.size(); i++) {
            if (i > 0) s.append(", ");
            s.append(models.get(i).id());
        }
        return s.append("]").toString();
    }

    /** 密钥脱敏：空→未配置；4 位及以下→全掩（防短 key 被还原）；否则显尾 4 位 + 长度（不落明文）。 */
    public static String maskKey(String key) {
        if (key == null || key.isBlank()) return "未配置";
        int n = key.length();
        String tail = n <= 4 ? "****" : key.substring(n - 4);
        return "已配置(尾4=" + tail + ", len=" + n + ")";
    }
}
