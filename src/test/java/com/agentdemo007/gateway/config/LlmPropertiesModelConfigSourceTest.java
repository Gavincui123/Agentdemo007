package com.agentdemo007.gateway.config;

import com.agentdemo007.intent.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.agentdemo007.gateway.config.RouteRule.RouteType;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真 {@link ModelConfigSource}（基于 {@link LlmProperties}）单测（主备容灾·T7）。
 *
 * <p>验证把 {@code llm.providers}（提供方列表）翻成 {@link ModelConfigSnapshot} 的收口逻辑：
 * <ol>
 *   <li>每 provider 注册两个模型 {@code {id}-large}/{@code {id}-small}，带 endpoint/apiKey/provider；</li>
 *   <li>主 provider（列表首位）打 RouteType 标签（大=REASONING/LONG_CONTEXT/STRUCTURED，小=SIMPLE），
 *       备 provider <b>不打标签</b>（仅经 fallback 链可达，不参与 byTag 主选）；</li>
 *   <li>5 条路由规则：CHIT_CHAT→主-small[备-small 链]，REASONING/LONG_CONTEXT/STRUCTURED_EXTRACTION/OTHER→主-large[备-large 链]。</li>
 * </ol>
 * 单 provider 退化为无备（备链空）；空 providers 退化为空快照（不阻塞装配）。
 */
class LlmPropertiesModelConfigSourceTest {

    @Test
    void load_buildsPrimaryBackupModelsAndRouteRules() {
        LlmProperties props = new LlmProperties();
        props.setEnabled(true);
        props.setProviders(List.of(
                provider("siliconflow", "https://api.siliconflow.cn/v1", "sk-sf",
                        "Qwen/Qwen3.5-35B-A3B", "Qwen/Qwen3.5-27B"),
                provider("deepseek", "https://token.sensenova.cn/v1", "sk-ds",
                        "deepseek-v4-flash", "sensenova-6.8-flash-lite")));

        ModelConfigSnapshot snap = new LlmPropertiesModelConfigSource(props).load();

        // ① 4 模型：2 主（打标）+ 2 备（不打标）
        assertThat(snap.models()).hasSize(4);

        ModelMetadata sfLarge = byId(snap, "siliconflow-large");
        assertThat(sfLarge.provider()).isEqualTo("siliconflow");
        assertThat(sfLarge.endpoint()).isEqualTo("https://api.siliconflow.cn/v1");
        assertThat(sfLarge.apiKey()).isEqualTo("sk-sf");
        assertThat(sfLarge.tags()).containsExactlyInAnyOrder("REASONING", "LONG_CONTEXT", "STRUCTURED");
        assertThat(sfLarge.isEnabled()).isTrue();

        ModelMetadata sfSmall = byId(snap, "siliconflow-small");
        assertThat(sfSmall.tags()).containsExactly("SIMPLE");

        ModelMetadata dsLarge = byId(snap, "deepseek-large");
        assertThat(dsLarge.provider()).isEqualTo("deepseek");
        assertThat(dsLarge.apiKey()).isEqualTo("sk-ds");
        assertThat(dsLarge.tags()).isEmpty(); // 备选不打标签

        ModelMetadata dsSmall = byId(snap, "deepseek-small");
        assertThat(dsSmall.tags()).isEmpty();

        // ② 路由规则：主 + 备链（同角色切备）
        RouteRule chitchat = ruleFor(snap, Intent.CHIT_CHAT);
        assertThat(chitchat.targetModelId()).isEqualTo("siliconflow-small");
        assertThat(chitchat.fallbackModelIds()).containsExactly("deepseek-small");

        RouteRule reasoning = ruleFor(snap, Intent.REASONING);
        assertThat(reasoning.targetModelId()).isEqualTo("siliconflow-large");
        assertThat(reasoning.fallbackModelIds()).containsExactly("deepseek-large");

        // 其余大模型意图同主同备
        assertThat(ruleFor(snap, Intent.LONG_CONTEXT).targetModelId()).isEqualTo("siliconflow-large");
        assertThat(ruleFor(snap, Intent.LONG_CONTEXT).fallbackModelIds()).containsExactly("deepseek-large");
        assertThat(ruleFor(snap, Intent.STRUCTURED_EXTRACTION).targetModelId()).isEqualTo("siliconflow-large");
        assertThat(ruleFor(snap, Intent.STRUCTURED_EXTRACTION).fallbackModelIds()).containsExactly("deepseek-large");
        assertThat(ruleFor(snap, Intent.OTHER).targetModelId()).isEqualTo("siliconflow-large");
        assertThat(ruleFor(snap, Intent.OTHER).fallbackModelIds()).containsExactly("deepseek-large");
    }

    @Test
    void singleProvider_degradesToNoBackup() {
        LlmProperties props = new LlmProperties();
        props.setEnabled(true);
        props.setProviders(List.of(
                provider("siliconflow", "https://x", "sk", "L", "S")));

        ModelConfigSnapshot snap = new LlmPropertiesModelConfigSource(props).load();

        assertThat(snap.models()).hasSize(2);
        assertThat(byId(snap, "siliconflow-large").tags()).isNotEmpty();
        // 无备：备链空（单模型退化）
        assertThat(ruleFor(snap, Intent.REASONING).fallbackModelIds()).isEmpty();
        assertThat(ruleFor(snap, Intent.CHIT_CHAT).targetModelId()).isEqualTo("siliconflow-small");
    }

    @Test
    void emptyProviders_yieldsEmptySnapshot() {
        ModelConfigSnapshot snap = new LlmPropertiesModelConfigSource(new LlmProperties()).load();
        assertThat(snap.models()).isEmpty();
        assertThat(snap.routeRules()).isEmpty();
    }

    // ---- helpers ----

    private static LlmProperties.Provider provider(String id, String url, String key, String large, String small) {
        LlmProperties.Provider p = new LlmProperties.Provider();
        p.setId(id);
        p.setBaseUrl(url);
        p.setApiKey(key);
        p.setLargeModel(large);
        p.setSmallModel(small);
        return p;
    }

    private static ModelMetadata byId(ModelConfigSnapshot s, String id) {
        return s.models().stream().filter(m -> m.id().equals(id))
                .findFirst().orElseThrow(() -> new AssertionError("缺模型：" + id));
    }

    private static RouteRule ruleFor(ModelConfigSnapshot s, Intent intent) {
        return s.routeRules().stream().filter(r -> r.intent() == intent)
                .findFirst().orElseThrow(() -> new AssertionError("缺规则：" + intent));
    }
}
