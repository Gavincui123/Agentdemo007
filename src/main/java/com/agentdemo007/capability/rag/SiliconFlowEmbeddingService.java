package com.agentdemo007.capability.rag;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SiliconFlow /v1/embeddings 嵌入执行器（第四层·prod 稠密向量化内核，OpenAI 兼容端点）。
 *
 * <p>每 provider 一个实例（由 {@code EmbeddingConfig} 按 {@code embedding.providers[*]} 注入
 * baseUrl/apiKey/model），POST {@code {base-url}/embeddings}，请求体
 * {@code {model, input, encoding_format:"float"}}（官方契约），解析 {@code data[0].embedding} → {@code float[]}，
 * 灌入 {@link InMemoryVectorStore} 供稠密余弦召回。
 *
 * <p><b>主备容灾</b>：本类是叶子（单 provider），由 {@code FailoverEmbeddingService} 逐 provider 尝试；
 * 主备全失败→抛异常→{@link HybridRetriever} 捕获稠密异常、降级稀疏(BM25)继续（不回退 HashEmbedding——
 * 索引真向量与查询 hash 向量空间不一致，余弦无意义；稀疏兜底是混合检索的韧性所在，②每步降级不阻塞）。
 *
 * <p>api-key 空→抛（provider 未配 key 即降级）。HTTP 4xx/5xx/超时原样上抛供主备切换。
 * 空文本返回零向量（{@code float[0]}，不调 API；{@link InMemoryVectorStore} 零范数→空召回，契约同 dev）。
 */
public class SiliconFlowEmbeddingService implements EmbeddingService {

    private static final String EMBEDDINGS_PATH = "/embeddings";

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public SiliconFlowEmbeddingService(String baseUrl, String apiKey, String model,
                                       RestTemplate restTemplate, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[0]; // 空文本零向量（不调 API；InMemoryVectorStore 零范数→空召回）
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new RuntimeException("embedding provider api-key 未配置（model=" + model + "）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", text);
        body.put("encoding_format", "float");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String resp;
        try {
            HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
            resp = restTemplate.postForObject(baseUrl + EMBEDDINGS_PATH, entity, String.class);
        } catch (RuntimeException e) {
            throw e; // HTTP 4xx/5xx/超时 → FailoverEmbeddingService 切备 / HybridRetriever 降级稀疏
        } catch (Exception e) {
            throw new RuntimeException("embedding 请求构建失败：" + e.getMessage(), e);
        }
        try {
            JsonNode data = mapper.readTree(resp).path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new RuntimeException("embedding 响应 data 为空：model=" + model);
            }
            JsonNode emb = data.get(0).path("embedding");
            if (!emb.isArray() || emb.isEmpty()) {
                throw new RuntimeException("embedding 响应无 embedding 字段：model=" + model);
            }
            float[] vec = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                vec[i] = (float) emb.get(i).asDouble();
            }
            return vec;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("embedding 响应解析失败：" + e.getMessage(), e);
        }
    }
}
