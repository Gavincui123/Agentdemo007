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
 * 真重排装配（{@code reranker.enabled=true} 时激活）。
 *
 * <p>把 {@link RerankerProperties} 装配为可注入 bean，按 providers 构造：每 provider 一个
 * {@link SiliconFlowReranker}（叶子），包进 {@link FailoverReranker}（主备逐 provider 尝试，
 * 全失败→降级 {@link Bm25Reranker}，重排只改顺序安全）。对外暴露为唯一 {@link Reranker} bean——
 * {@link VectorStoreConfig} 的 dev BM25 退让。
 *
 * <p>与 {@link VectorStoreConfig} 互斥：以 {@code reranker.enabled} 属性门控（非 bean 存在性）。
 * {@code true} 走本类真重排+主备，{@code false}/缺省走 dev BM25。
 */
@Configuration
@EnableConfigurationProperties(RerankerProperties.class)
@ConditionalOnProperty(prefix = "reranker", name = "enabled", havingValue = "true")
public class RerankerConfig {

    @Bean
    RestTemplate rerankerRestTemplate() {
        // 超时预算治理：裸 RestTemplate 无超时（连接/读取无限挂起）。15s 读超时 = 查询重排健康
        // 耗时（亚秒~2s）的 7 倍+ 余量；超时→FailoverReranker 降级 BM25，不阻塞链路。
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(15_000);
        return new RestTemplate(f);
    }

    @Bean
    ObjectMapper rerankerObjectMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * 可单测的工厂：把 providers 翻成主备重排器——每 provider 一个 {@link SiliconFlowReranker}，
     * 包进 {@link FailoverReranker}（主备逐 provider 尝试，全失败→降级 BM25）。
     */
    static FailoverReranker buildReranker(RerankerProperties props, RestTemplate rt, ObjectMapper mapper) {
        List<Reranker> providers = new ArrayList<>();
        for (RerankerProperties.Provider p : props.getProviders()) {
            providers.add(new SiliconFlowReranker(p.getBaseUrl(), p.getApiKey(), p.getModel(), rt, mapper));
        }
        return new FailoverReranker(providers, new Bm25Reranker());
    }

    @Bean
    Reranker reranker(RerankerProperties props,
                     RestTemplate rerankerRestTemplate,
                     ObjectMapper rerankerObjectMapper) {
        // 熔断守卫包在每个 API provider 上（不能包 FailoverReranker 外层——它内部吞错降级 BM25，
        // 外层守卫永远看不到失败）：provider 故障期首次失败即开断路（冷却 30s 半开探测），
        // 后续调用瞬时失败 → FailoverReranker 立即落 BM25，不再每轮吃满读超时（实测首轮因此 +30s）。
        CircuitBreakerGuard guard = new CircuitBreakerGuard("reranker", 60_000, 30_000);
        List<Reranker> providers = new ArrayList<>();
        for (RerankerProperties.Provider p : props.getProviders()) {
            Reranker api = new SiliconFlowReranker(p.getBaseUrl(), p.getApiKey(), p.getModel(),
                    rerankerRestTemplate, rerankerObjectMapper);
            providers.add((query, candidates) -> guard.call(() -> api.rerank(query, candidates)));
        }
        return new FailoverReranker(providers, new Bm25Reranker());
    }
}
