package com.agentdemo007.prompt;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Properties;

/**
 * 提示词源 Spring 装配（[[q1-nacos-prompt-mgmt]] Gap A：NacosPromptSource @Bean）。
 *
 * <p>属性门控互斥：{@code app.prompt.source=nacos}→装配 {@link NacosPromptSource}（typed {@code AiService}
 * 拉取，SDK 内置 md5 缓存 + gRPC 热推，按 latest/版本/标签）；缺省/{@code local}→{@code GatewayConfig}
 * 装配 {@link LocalPromptSource}（dev 内存源）。两源同实现 {@link PromptRegistry}，消费方
 * （{@link com.agentdemo007.context.SystemAnchorLayer} 系统提示词 +
 * {@link com.agentdemo007.capability.workflow.WorkflowExecutionStep} 澄清话术）对源无感，registry 空则回退硬编码。
 *
 * <p>②每步降级：AiService 构造抛（Nacos 不可达/缺凭据）→回退 {@link LocalPromptSource}，不阻塞 context 启动
 * （消费方见空 registry→回退硬编码默认）。{@link AiServiceFactory} seam 让构造可注入假桩单测（无真实 Nacos）。
 *
 * <p>Nacos 连接参数复用 {@code spring.nacos.config.*}（与配置中心同源，非另立一套），经 {@link #buildNacosProps}
 * 翻成 SDK {@link PropertyKeyConst} 键。promptKey 命名：{@code system-prompt-segments}
 * （分段式系统提示词片段清单，bare YAML 数组，经 {@code SystemPromptAssembler} 装配）、
 * {@code clarify-return}/{@code clarify-refund}（澄清话术），在 Nacos 控制台「AI 资源→提示词模板」按 key 建模板。
 */
@Configuration
public class PromptSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(PromptSourceConfig.class);

    /** 默认 AiService 构造器：包 AiFactory.createAiService（真 Nacos，prod）。可被测试 @Bean 覆盖为假桩。 */
    @Bean
    AiServiceFactory aiServiceFactory() {
        return AiFactory::createAiService;
    }

    /**
     * Nacos 提示词源（{@code app.prompt.source=nacos} 时装配）。读 {@code spring.nacos.config.*} 连接参数
     * → 构造 {@code AiService} → 包 {@link NacosPromptSource}；构造失败→②降级 {@link LocalPromptSource}。
     */
    @Bean
    @ConditionalOnProperty(name = "app.prompt.source", havingValue = "nacos")
    PromptRegistry nacosPromptSource(Environment env, AiServiceFactory factory) {
        return buildNacosPromptSource(factory, buildNacosProps(env));
    }

    /** 可测 seam：按 factory 构造 AiService→NacosPromptSource；抛→LocalPromptSource 降级（不阻塞 context）。 */
    static PromptRegistry buildNacosPromptSource(AiServiceFactory factory, Properties props) {
        try {
            return new NacosPromptSource(factory.create(props));
        } catch (Exception e) {
            log.warn("Nacos AiService 构造失败，降级 LocalPromptSource（②每步降级，不阻塞 context）：{}",
                    e.getMessage());
            return new LocalPromptSource();
        }
    }

    /** 把 spring.nacos.config.* 翻成 Nacos SDK PropertyKeyConst 键（server-addr/namespace/username/password）。 */
    static Properties buildNacosProps(Environment env) {
        Properties p = new Properties();
        putIfPresent(p, PropertyKeyConst.SERVER_ADDR, env.getProperty("spring.nacos.config.server-addr"));
        putIfPresent(p, PropertyKeyConst.NAMESPACE, env.getProperty("spring.nacos.config.namespace"));
        putIfPresent(p, PropertyKeyConst.USERNAME, env.getProperty("spring.nacos.config.username"));
        putIfPresent(p, PropertyKeyConst.PASSWORD, env.getProperty("spring.nacos.config.password"));
        return p;
    }

    private static void putIfPresent(Properties p, String key, String val) {
        if (val != null && !val.isBlank()) {
            p.setProperty(key, val);
        }
    }
}
