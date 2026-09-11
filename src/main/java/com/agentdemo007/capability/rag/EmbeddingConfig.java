package com.agentdemo007.capability.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 真嵌入装配（{@code embedding.enabled=true} 时激活）。
 *
 * <p>把 {@link EmbeddingProperties} 装配为可注入 bean，按 providers 构造：每 provider 一个
 * {@link SiliconFlowEmbeddingService}（叶子，含端点/密钥/模型），包进 {@link FailoverEmbeddingService}
 * （主备逐 provider 尝试，全失败→抛→{@link HybridRetriever} 降级稀疏-only）。对外暴露为唯一
 * {@link EmbeddingService} bean——{@link VectorStoreConfig} 的 dev {@link HashEmbeddingService}
 * （{@code @ConditionalOnProperty enabled=false/缺省}）退让。
 *
 * <p>与 {@link VectorStoreConfig} 互斥：以 {@code embedding.enabled} 属性门控（非 bean 存在性），
 * 装配顺序无关、可调试——{@code true} 走本类真嵌入，{@code false}/缺省走 dev HashEmbedding。
 * RestTemplate/ObjectMapper 本类自持（命名 bean，按名注入，与 LlmConfig 的并存不冲突）。
 */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
@ConditionalOnProperty(prefix = "embedding", name = "enabled", havingValue = "true")
public class EmbeddingConfig {

    @Bean
    RestTemplate embeddingRestTemplate() {
        return new RestTemplate();
    }

    @Bean
    ObjectMapper embeddingObjectMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * 可单测的工厂：把 providers 翻成主备嵌入服务——每 provider 一个 {@link SiliconFlowEmbeddingService}，
     * 包进 {@link FailoverEmbeddingService}（主备逐 provider 尝试）。
     */
    static FailoverEmbeddingService buildEmbeddingService(EmbeddingProperties props,
                                                          RestTemplate rt, ObjectMapper mapper) {
        List<EmbeddingService> providers = new ArrayList<>();
        for (EmbeddingProperties.Provider p : props.getProviders()) {
            providers.add(new SiliconFlowEmbeddingService(p.getBaseUrl(), p.getApiKey(), p.getModel(), rt, mapper));
        }
        return new FailoverEmbeddingService(providers);
    }

    @Bean
    EmbeddingService embeddingService(EmbeddingProperties props,
                                      RestTemplate embeddingRestTemplate,
                                      ObjectMapper embeddingObjectMapper) {
        return buildEmbeddingService(props, embeddingRestTemplate, embeddingObjectMapper);
    }
}
