package com.agentdemo007.prompt;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import com.alibaba.nacos.api.exception.NacosException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nacos 提示词源测试（prod：typed AiService，SDK 类型不外泄）。
 *
 * <p>验证：版本规格分派到对应 AiService 方法（latest→getPrompt / version→getPromptByVersion /
 * label→getPromptByLabel）；SDK {@link Prompt} 映射为收口 {@link PromptTemplate}；
 * Nacos 异常或空返回 → {@code Optional.empty()}（②每步降级，调用方回退本地默认模板）。
 *
 * <p>{@link AiService} 以 Mockito mock 注入，无真实 Nacos 依赖。
 */
class NacosPromptSourceTest {

    private final AiService aiService = mock(AiService.class);
    private final NacosPromptSource source = new NacosPromptSource(aiService);

    private Prompt sdkPrompt(String key, String version, String template, String md5) {
        Prompt p = new Prompt();
        p.setPromptKey(key);
        p.setVersion(version);
        p.setTemplate(template);
        p.setMd5(md5);
        return p;
    }

    @Test
    void getLatest_callsGetPromptAndMaps() throws Exception {
        when(aiService.getPrompt("greet"))
                .thenReturn(sdkPrompt("greet", "1.0", "你好 {{name}}", "md5-greet"));

        Optional<PromptTemplate> result = source.get("greet", VersionSpec.latest());

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(new PromptTemplate("greet", "1.0", "你好 {{name}}", "md5-greet"));
        verify(aiService).getPrompt("greet");
    }

    @Test
    void getByVersion_dispatchesToGetPromptByVersion() throws Exception {
        when(aiService.getPromptByVersion("greet", "1.0"))
                .thenReturn(sdkPrompt("greet", "1.0", "t", "m"));

        source.get("greet", VersionSpec.version("1.0"));

        verify(aiService).getPromptByVersion("greet", "1.0");
    }

    @Test
    void getByLabel_dispatchesToGetPromptByLabel() throws Exception {
        when(aiService.getPromptByLabel("greet", "stable"))
                .thenReturn(sdkPrompt("greet", "1.0", "t", "m"));

        source.get("greet", VersionSpec.label("stable"));

        verify(aiService).getPromptByLabel("greet", "stable");
    }

    @Test
    void nacosException_returnsEmpty() throws Exception {
        when(aiService.getPrompt("greet")).thenThrow(new NacosException(500, "nacos down"));

        assertThat(source.get("greet", VersionSpec.latest())).isEmpty();
    }

    @Test
    void nullPrompt_returnsEmpty() throws Exception {
        when(aiService.getPrompt("greet")).thenReturn(null);

        assertThat(source.get("greet", VersionSpec.latest())).isEmpty();
    }
}
