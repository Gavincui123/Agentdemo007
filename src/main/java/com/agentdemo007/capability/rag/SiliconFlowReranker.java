package com.agentdemo007.capability.rag;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SiliconFlow /v1/rerank 重排执行器（第四层·prod 真模型重排）。
 *
 * <p>每 provider 一个实例（由 {@code RerankerConfig} 按 {@code reranker.providers[*]} 注入
 * baseUrl/apiKey/model），POST {@code {base-url}/rerank}，请求体
 * {@code {model, query, documents:[候选文本...], top_n, return_documents:false}}（官方契约），
 * 解析 {@code results[].index + relevance_score}，按相关性降序重排候选（保留 source/temporal/domain
 * 字段与检索置信度 score，仅写 {@code relevance} + 顺序——置信度终闸按 relevance 裁决被重排片段）。
 * 响应已按相关性排序，本类仍防御性排序。
 *
 * <p><b>主备容灾</b>：本类是叶子（单 provider），由 {@code FailoverReranker} 逐 provider 尝试；
 * 主备全失败→降级 {@link Bm25Reranker}（重排只改顺序，降级安全不阻塞、不污染向量空间）。
 *
 * <p>api-key 空→抛（provider 未配 key 即降级）。HTTP 4xx/5xx/超时原样上抛供主备切换。
 * 空查询/空候选保留召回序、不调 API（契约同 BM25，无信号不重排）。
 *
 * <p>不施加 BM25 时效衰减——真模型按语义相关性打分优于启发式衰减；历史片段由
 * {@link RagFragment#displayText()} 时效标注隔离（过时不冒充当前，§5.4.1）。
 */
public class SiliconFlowReranker implements Reranker {

    private static final String RERANK_PATH = "/rerank";

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public SiliconFlowReranker(String baseUrl, String apiKey, String model,
                               RestTemplate restTemplate, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    @Override
    public List<RagFragment> rerank(String query, List<RagFragment> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return new ArrayList<>(candidates); // 无信号保留召回序
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new RuntimeException("rerank provider api-key 未配置（model=" + model + "）");
        }

        List<String> documents = new ArrayList<>();
        for (RagFragment f : candidates) {
            documents.add(f.text());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", documents.size());
        body.put("return_documents", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String resp;
        try {
            HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
            resp = restTemplate.postForObject(baseUrl + RERANK_PATH, entity, String.class);
        } catch (RuntimeException e) {
            throw e; // HTTP 4xx/5xx/超时 → FailoverReranker 切备
        } catch (Exception e) {
            throw new RuntimeException("rerank 请求构建失败：" + e.getMessage(), e);
        }
        try {
            JsonNode results = mapper.readTree(resp).path("results");
            if (!results.isArray() || results.isEmpty()) {
                throw new RuntimeException("rerank 响应 results 为空：model=" + model);
            }
            List<JsonNode> sorted = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                sorted.add(results.get(i));
            }
            sorted.sort(Comparator.comparingDouble(
                    (JsonNode r) -> r.path("relevance_score").asDouble(0)).reversed());

            List<RagFragment> reranked = new ArrayList<>();
            Set<Integer> used = new HashSet<>();
            for (JsonNode r : sorted) {
                int idx = r.path("index").asInt(-1);
                if (idx >= 0 && idx < candidates.size() && used.add(idx)) {
                    RagFragment f = candidates.get(idx);
                    // 相关度写 relevance（不覆写 score）——检索置信度证据保留，置信度终闸双判据：
                    // 被重排的看 relevance，未重排/降级重排的看 score（cosine 口径）
                    reranked.add(new RagFragment(f.text(), f.score(), f.source(),
                            f.timestamp(), f.validUntil(), f.temporalTag(),
                            f.domain(), r.path("relevance_score").asDouble(0), f.cosineScored()));
                }
            }
            // 未返回的候选保留召回序（防御：top_n < candidates.size() 时补齐，不丢候选）
            for (int i = 0; i < candidates.size(); i++) {
                if (!used.contains(i)) {
                    reranked.add(candidates.get(i));
                }
            }
            return reranked;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("rerank 响应解析失败：" + e.getMessage(), e);
        }
    }
}
