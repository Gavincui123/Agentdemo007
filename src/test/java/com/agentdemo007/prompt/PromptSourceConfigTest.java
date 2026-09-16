package com.agentdemo007.prompt;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link PromptSourceConfig} 单测（[[q1-nacos-prompt-mgmt]] Gap A：NacosPromptSource @Bean 装配）。
 *
 * <p>核心可测 seam：{@link PromptSourceConfig#buildNacosPromptSource(AiServiceFactory, Properties)}
 * 注入 {@link AiServiceFactory}（默认 @Bean 包 {@code AiFactory::createAiService}，测试传 lambda 假桩），
 * 构造成功→{@link NacosPromptSource}；AiService 构造抛（Nacos 不可达/缺凭据）→②降级 {@link LocalPromptSource}，
 * 不阻塞 context 启动（②每步降级）。{@link #buildNacosProps} 把 {@code spring.nacos.config.*} 翻成
 * Nacos SDK 的 {@link PropertyKeyConst} 键。
 */
class PromptSourceConfigTest {

    @Test
    void buildNacosPromptSource_factoryReturnsAiService_returnsNacosPromptSource() throws Exception {
        AiService ai = mock(AiService.class);
        AiServiceFactory factory = props -> ai;

        PromptRegistry result = PromptSourceConfig.buildNacosPromptSource(factory, new Properties());

        assertThat(result).isInstanceOf(NacosPromptSource.class); // 构造成功→真 Nacos 源
    }

    @Test
    void buildNacosPromptSource_factoryThrows_fallsBackToLocalPromptSource() {
        AiServiceFactory factory = props -> { throw new NacosException(500, "nacos down"); };

        PromptRegistry result = PromptSourceConfig.buildNacosPromptSource(factory, new Properties());

        assertThat(result).isInstanceOf(LocalPromptSource.class); // ②降级：不阻塞 context
    }

    @Test
    void buildNacosProps_readsSpringNacosConfigKeys() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.nacos.config.server-addr", "127.0.0.1:8848");
        env.setProperty("spring.nacos.config.namespace", "ns1");
        env.setProperty("spring.nacos.config.username", "u");
        env.setProperty("spring.nacos.config.password", "p");

        Properties props = PromptSourceConfig.buildNacosProps(env);

        assertThat(props.getProperty(PropertyKeyConst.SERVER_ADDR)).isEqualTo("127.0.0.1:8848");
        assertThat(props.getProperty(PropertyKeyConst.NAMESPACE)).isEqualTo("ns1");
        assertThat(props.getProperty(PropertyKeyConst.USERNAME)).isEqualTo("u");
        assertThat(props.getProperty(PropertyKeyConst.PASSWORD)).isEqualTo("p");
    }

    @Test
    void buildNacosProps_absentKeys_produceEmptyProps() {
        // spring.nacos.config.* 全缺（dev 未配 Nacos）→ 空 Properties，不抛（调用方 factory.create 会自行降级）
        Properties props = PromptSourceConfig.buildNacosProps(new MockEnvironment());

        assertThat(props).isEmpty();
    }
}
