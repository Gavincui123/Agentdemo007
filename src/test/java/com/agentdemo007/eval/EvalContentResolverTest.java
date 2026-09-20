package com.agentdemo007.eval;

import com.alibaba.nacos.api.config.ConfigService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 评测黄金集内容解析器单测（2026-09-17 Nacos 动态化）。
 *
 * <p>覆盖来源决策矩阵：Nacos 命中→NACOS；读取异常/空内容→逐 stage 回退本地 classpath（LOCAL）；
 * 禁用/无 ConfigService→恒本地（②每步降级：解析器永不抛，端点恒可用）。
 * {@link ConfigService} 为 Mockito 假桩，不连真 Nacos。
 */
class EvalContentResolverTest {

    private static final String GROUP = "DEFAULT_GROUP";
    private static final String PREFIX = "agentdemo-eval";
    private static final long TIMEOUT = 3000L;

    private final ConfigService configService = mock(ConfigService.class);

    private static final String STAGE_RESOURCE = "eval/injection.json";
    private static final String DATA_ID = PREFIX + "-injection.json";

    @Test
    void resolve_nacosHasContent_returnsNacosSource() throws Exception {
        when(configService.getConfig(DATA_ID, GROUP, TIMEOUT))
                .thenReturn("{\"stage\":\"injection\",\"description\":\"Nacos 版\",\"cases\":[]}");
        EvalContentResolver resolver =
                new EvalContentResolver(configService, true, GROUP, PREFIX, TIMEOUT);

        EvalContentResolver.Resolved resolved = resolver.resolve(STAGE_RESOURCE);

        assertThat(resolved.source()).isEqualTo(EvalContentResolver.SOURCE_NACOS);
        assertThat(resolved.content()).contains("Nacos 版");
    }

    @Test
    void resolve_nacosThrows_fallsBackToLocal() throws Exception {
        when(configService.getConfig(DATA_ID, GROUP, TIMEOUT))
                .thenThrow(new RuntimeException("nacos down"));
        EvalContentResolver resolver =
                new EvalContentResolver(configService, true, GROUP, PREFIX, TIMEOUT);

        EvalContentResolver.Resolved resolved = resolver.resolve(STAGE_RESOURCE);

        assertThat(resolved.source()).isEqualTo(EvalContentResolver.SOURCE_LOCAL);
        assertThat(resolved.content()).contains("injection"); // 本地 classpath 黄金集兜底
    }

    @Test
    void resolve_nacosBlankContent_fallsBackToLocal() throws Exception {
        // dataId 未在 Nacos 创建（getConfig 返回 null）或内容为空 → 本地兜底
        when(configService.getConfig(DATA_ID, GROUP, TIMEOUT)).thenReturn("   ");
        EvalContentResolver resolver =
                new EvalContentResolver(configService, true, GROUP, PREFIX, TIMEOUT);

        EvalContentResolver.Resolved resolved = resolver.resolve(STAGE_RESOURCE);

        assertThat(resolved.source()).isEqualTo(EvalContentResolver.SOURCE_LOCAL);
        assertThat(resolved.content()).isNotBlank();
    }

    @Test
    void resolve_disabled_neverTouchesNacos() {
        EvalContentResolver resolver =
                new EvalContentResolver(configService, false, GROUP, PREFIX, TIMEOUT);

        EvalContentResolver.Resolved resolved = resolver.resolve(STAGE_RESOURCE);

        assertThat(resolved.source()).isEqualTo(EvalContentResolver.SOURCE_LOCAL);
        verifyNoInteractions(configService);
    }

    @Test
    void resolve_nullConfigService_fallsBackToLocalWithoutThrow() {
        // from() 构造降级产物：service=null（Nacos 不可达）→ 恒本地，不抛
        EvalContentResolver resolver =
                new EvalContentResolver(null, false, GROUP, PREFIX, TIMEOUT);

        EvalContentResolver.Resolved resolved = resolver.resolve(STAGE_RESOURCE);

        assertThat(resolved.source()).isEqualTo(EvalContentResolver.SOURCE_LOCAL);
        assertThat(resolved.content()).isNotBlank();
    }

    @Test
    void stageOf_extractsStageNameFromResourcePath() {
        assertThat(EvalContentResolver.stageOf("eval/injection.json")).isEqualTo("injection");
        assertThat(EvalContentResolver.stageOf("eval/rag-redteam.json")).isEqualTo("rag-redteam");
    }
}
