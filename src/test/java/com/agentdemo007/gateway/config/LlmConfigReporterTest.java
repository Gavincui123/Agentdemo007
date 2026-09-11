package com.agentdemo007.gateway.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmConfigReporter} 单测（主备容灾·启动配置可观测）。
 *
 * <p>断言：① 输出含 provider id / base-url / 脱敏 key 标记 / large-small 模型串 / 路由主+备链 / 熔断参数；
 * ② <b>不含</b>原始 key 全文（脱敏不泄密）；③ props=null（dev）走占位源分支。
 *
 * <p>snapshot 经 {@link LlmPropertiesModelConfigSource#load()} 真实派生——与生产装配同构，
 * 不手搓快照，确保报告的路由主/备链与运行时一致。
 */
class LlmConfigReporterTest {

    private static final String SF_KEY = "sk-secret-1234567890abcdef";   // 尾4=cdef
    private static final String DS_KEY = "sk-otherkey-XYZ-9876";          // 尾4=9876

    private LlmProperties twoProviders() {
        LlmProperties p = new LlmProperties();
        p.setEnabled(true);
        p.setProviders(List.of(
                provider("siliconflow", "https://api.siliconflow.cn/v1", SF_KEY,
                        "Qwen/Qwen3.5-35B-A3B", "Qwen/Qwen3.5-27B"),
                provider("deepseek", "https://token.sensenova.cn/v1", DS_KEY,
                        "deepseek-v4-flash", "sensenova-6.8-flash-lite")));
        p.setCircuitBreaker(cb(5, 60000L, 30000L));
        return p;
    }

    private static LlmProperties.Provider provider(String id, String url, String key, String large, String small) {
        LlmProperties.Provider p = new LlmProperties.Provider();
        p.setId(id); p.setBaseUrl(url); p.setApiKey(key); p.setLargeModel(large); p.setSmallModel(small);
        return p;
    }

    private static LlmProperties.CircuitBreaker cb(int threshold, long window, long cooldown) {
        LlmProperties.CircuitBreaker c = new LlmProperties.CircuitBreaker();
        c.setFailureThreshold(threshold); c.setWindowMs(window); c.setCooldownMs(cooldown);
        return c;
    }

    @Test
    void report_masksKeysShowsProvidersRoutesAndBreaker() {
        LlmProperties props = twoProviders();
        ModelConfigSnapshot snapshot = new LlmPropertiesModelConfigSource(props).load();

        String report = LlmConfigReporter.report(props, snapshot);

        // ① 结构化内容可见：provider / base-url / 模型串 / 路由主+备 / 熔断参数 / 主备标记
        assertThat(report).contains("siliconflow", "https://api.siliconflow.cn/v1");
        assertThat(report).contains("deepseek", "https://token.sensenova.cn/v1");
        assertThat(report).contains("Qwen/Qwen3.5-35B-A3B", "Qwen/Qwen3.5-27B");
        assertThat(report).contains("deepseek-v4-flash", "sensenova-6.8-flash-lite");
        assertThat(report).contains("已配置", "尾4=", "len=");
        assertThat(report).contains("CHIT_CHAT", "siliconflow-small", "deepseek-small");
        assertThat(report).contains("REASONING", "siliconflow-large", "deepseek-large");
        assertThat(report).contains("threshold=5", "window=60000", "cooldown=30000");
        assertThat(report).contains("[主]", "[备]");
        assertThat(report).contains("启用模型 4 个");

        // ② 不落明文：原文 key 全文 / 中段 不得出现
        assertThat(report).doesNotContain(SF_KEY);
        assertThat(report).doesNotContain("secret-1234567890");
        assertThat(report).doesNotContain(DS_KEY);
        assertThat(report).doesNotContain("otherkey-XYZ");
        // 尾4 指纹应出现（确认是哪把 key、但不泄全文）
        assertThat(report).contains("尾4=cdef", "尾4=9876");
    }

    @Test
    void report_devWhenPropsNull() {
        ModelConfigSnapshot devSnapshot = new ModelConfigSnapshot(
                List.of(ModelMetadata.builder("dev-noop").build()), List.of(), null, null);

        String report = LlmConfigReporter.report(null, devSnapshot);

        assertThat(report).contains("dev 占位源", "dev-noop", "启用模型 1 个");
    }

    @Test
    void maskKey_nullOrBlank_returns未配置() {
        assertThat(LlmConfigReporter.maskKey(null)).isEqualTo("未配置");
        assertThat(LlmConfigReporter.maskKey("")).isEqualTo("未配置");
        assertThat(LlmConfigReporter.maskKey("   ")).isEqualTo("未配置");
    }

    @Test
    void maskKey_shortValue_fullyMasked_noLeak() {
        // 4 位及以下 key 不显尾4（防短 key 被全量还原），只显 **** + len
        String report = LlmConfigReporter.maskKey("abcd");
        assertThat(report).contains("****", "len=4");
        assertThat(report).doesNotContain("abcd");
    }

    /**
     * 思考开关状态可见：{@code llm.thinking.enabled} + 每 provider 各自关思考参数应在报告中可读——
     * 否则「读了什么」不可审（闲聊恒关 / 非闲聊开关是否开、SiliconFlow enable_thinking=false /
     * SenseNova reasoning_effort=none 是否就位无法核对）。覆盖开/关两态。
     */
    @Test
    void report_showsThinkingSwitchAndPerProviderParams() {
        LlmProperties props = new LlmProperties();
        props.setEnabled(true);
        LlmProperties.Provider sf = provider("siliconflow", "https://api.siliconflow.cn/v1", SF_KEY,
                "Qwen/Qwen3.5-35B-A3B", "Qwen/Qwen3.5-27B");
        sf.setDisableThinkingParams(Map.<String, Object>of("enable_thinking", false));
        LlmProperties.Provider ds = provider("deepseek", "https://token.sensenova.cn/v1", DS_KEY,
                "deepseek-v4-flash", "sensenova-6.8-flash-lite");
        ds.setDisableThinkingParams(Map.<String, Object>of("reasoning_effort", "none"));
        props.setProviders(List.of(sf, ds));
        props.setCircuitBreaker(cb(5, 60000L, 30000L));

        // 非闲聊关思考（thinking.enabled=false）
        props.getThinking().setEnabled(false);
        String off = LlmConfigReporter.report(props, new LlmPropertiesModelConfigSource(props).load());
        assertThat(off).contains("思考=意图驱动(闲聊恒关; 非闲聊 thinking.enabled=false=关)");
        assertThat(off).contains("关思考参数={enable_thinking=false}", "关思考参数={reasoning_effort=none}");

        // 非闲聊开思考（thinking.enabled=true，默认）
        props.getThinking().setEnabled(true);
        String on = LlmConfigReporter.report(props, new LlmPropertiesModelConfigSource(props).load());
        assertThat(on).contains("思考=意图驱动(闲聊恒关; 非闲聊 thinking.enabled=true=开)");
        assertThat(on).contains("关思考参数={enable_thinking=false}", "关思考参数={reasoning_effort=none}");
    }
}
