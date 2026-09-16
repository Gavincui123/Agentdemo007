package com.agentdemo007.prompt;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;

import java.util.Properties;

/**
 * AiService 构造 seam（[[q1-nacos-prompt-mgmt]] Gap A）。
 *
 * <p>默认 @Bean（{@link PromptSourceConfig#aiServiceFactory}）包 {@code AiFactory::createAiService}
 * （真 Nacos，prod）；测试/装配可注入假桩（返 mock {@link AiService} 或抛，证降级路径，无真实 Nacos 依赖）。
 * 镜像 {@code LangChain4jModelExecutor} 的 {@code HttpClientBuilder} seam 范式（委托非重写）。
 */
@FunctionalInterface
public interface AiServiceFactory {

    /** 按 Nacos 连接 props 构造 {@link AiService}（默认包 AiFactory.createAiService）。 */
    AiService create(Properties props) throws NacosException;
}
