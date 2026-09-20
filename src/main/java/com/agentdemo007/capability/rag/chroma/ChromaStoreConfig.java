package com.agentdemo007.capability.rag.chroma;

import com.agentdemo007.capability.rag.CircuitBreakerGuard;
import com.agentdemo007.capability.rag.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 真库装配（{@code vectorstore.type=chroma} 时激活，真实 RAG）。
 *
 * <p>装配：{@link ChromaRestClient}（实测 v2 路由）→ {@link ChromaVectorStore}（VectorStore seam，
 * 稠密通道）+ {@code LuceneBm25IndexService}/{@code LuceneBm25Retriever}（稀疏通道——Lucene 磁盘
 * 倒排 BM25，启动时从 Chroma 分页流式同步，不占堆内存）→ {@code ChromaHybridRetriever}
 * （{@code @Primary} 主检索器，替换 dev {@code HybridRetriever}）。与 {@code VectorStoreConfig}
 * 属性门控互斥（{@code vectorstore.type}=chroma|inmemory，非 bean 存在性——项目惯例，避免装配竞态）。
 *
 * <p>降级语义（②每步降级）：集合缺失/Chroma 不可达 → 启动告警不阻塞（稠密通道集合懒解析、
 * 入库后自动恢复；Lucene 同步中止沿用现有磁盘索引）；embedding.enabled=false → 启动即告警
 * （hash 假向量 64 维 vs 集合 4096 维必不匹配，检索将降级 Lucene-only）。熔断守卫同
 * embedding/rerank 口径（单次失败快速降级）。
 */
@Configuration
@EnableConfigurationProperties(ChromaProperties.class)
@ConditionalOnProperty(prefix = "vectorstore", name = "type", havingValue = "chroma")
public class ChromaStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(ChromaStoreConfig.class);

    @Bean
    RestTemplate chromaRestTemplate(ChromaProperties props) {
        // 超时预算治理：裸 RestTemplate 无超时（连接/读取无限挂起），对齐 embedding/reranker 惯例
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(props.getConnectTimeoutMs());
        f.setReadTimeout(props.getReadTimeoutMs());
        return new RestTemplate(f);
    }

    @Bean
    ObjectMapper chromaObjectMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    ChromaRestClient chromaRestClient(ChromaProperties props,
                                      RestTemplate chromaRestTemplate,
                                      ObjectMapper chromaObjectMapper) {
        return new ChromaRestClient(props.getBaseUrl(), props.getApiKey(),
                props.getTenant(), props.getDatabase(), chromaRestTemplate, chromaObjectMapper);
    }

    @Bean
    ChromaVectorStore chromaVectorStore(ChromaRestClient chromaRestClient,
                                        ChromaProperties props,
                                        com.agentdemo007.capability.rag.EmbeddingService embeddingService,
                                        @Value("${embedding.enabled:false}")
                                        boolean embeddingEnabled) {
        if (!embeddingEnabled) {
            log.warn("vectorstore.type=chroma 但 embedding.enabled=false：hash 假向量（64 维）与真库集合"
                    + "（4096 维）必然不匹配，稠密检索将抛维度异常降级 BM25-only——"
                    + "真库模式须 EMBEDDING_ENABLED=true 且模型与入库一致（Qwen/Qwen3-Embedding-8B）");
        }
        boolean alive = chromaRestClient.heartbeat();
        log.info("Chroma 真库模式装配：base-url={} collection={} heartbeat={}",
                props.getBaseUrl(), props.getCollection(), alive ? "OK" : "不可达（集合懒解析，入库后自动恢复）");
        return new ChromaVectorStore(chromaRestClient, props, embeddingService,
                new CircuitBreakerGuard("chroma", 60_000, 30_000));
    }

    /**
     * 主检索器（真库）：稠密（Chroma 余弦）+ 稀疏（Lucene 磁盘倒排 BM25）宽召回融合，
     * {@code @Primary} 接管 dev {@code VectorStoreConfig#hybridRetriever}（属性门控互斥）。
     * {@code app.rag.recall-sparse}：稀疏召回预算（稠密预算由 RagStep 传 recall-dense）。
     */
    @Bean
    @Primary
    Retriever chromaHybridRetriever(com.agentdemo007.capability.rag.VectorRetriever denseChannel,
                                    com.agentdemo007.capability.rag.lucene.LuceneBm25Retriever sparseChannel,
                                    @Value("${app.rag.recall-sparse:24}")
                                    int recallSparse) {
        return new com.agentdemo007.capability.rag.chroma.ChromaHybridRetriever(denseChannel, sparseChannel, recallSparse);
    }

    /**
     * Lucene 磁盘倒排 BM25 索引服务（稀疏通道生产实现）：装配即打开索引目录并从 Chroma
     * 分页流式同步（增量 upsert + 陈旧清理）。同步失败（Chroma 不可达/集合缺失）→ 告警，
     * 沿用现有磁盘索引继续服务，不阻塞启动（②每步降级）。
     * {@code app.rag.lucene-dir}：索引落盘目录（MMap，堆内存极小，OS 页缓存承载读放大）。
     */
    @Bean(destroyMethod = "close")
    com.agentdemo007.capability.rag.lucene.LuceneBm25IndexService luceneBm25IndexService(
            ChromaRestClient chromaRestClient,
            ChromaProperties props,
            @Value("${app.rag.lucene-dir:./data/lucene-bm25}") String luceneDir) throws java.io.IOException {
        com.agentdemo007.capability.rag.lucene.LuceneBm25IndexService service =
                new com.agentdemo007.capability.rag.lucene.LuceneBm25IndexService(
                        chromaRestClient, props, java.nio.file.Path.of(luceneDir));
        service.syncFromChroma();
        return service;
    }

    @Bean
    com.agentdemo007.capability.rag.lucene.LuceneBm25Retriever luceneBm25Retriever(
            com.agentdemo007.capability.rag.lucene.LuceneBm25IndexService luceneBm25IndexService) {
        return new com.agentdemo007.capability.rag.lucene.LuceneBm25Retriever(luceneBm25IndexService);
    }
}
