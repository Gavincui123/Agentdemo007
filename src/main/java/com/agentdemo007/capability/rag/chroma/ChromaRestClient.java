package com.agentdemo007.capability.rag.chroma;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chroma v2 REST 客户端（真实 RAG·叶子 HTTP 执行器，端点路由经远程实测确认）。
 *
 * <p>路由（chromadb 1.0.x v2 API，无/可选 Bearer）：
 * <ul>
 *   <li>{@code GET  /api/v2/heartbeat}——存活探测；</li>
 *   <li>{@code GET  /api/v2/tenants/{t}/databases/{d}/collections}——集合列表
 *       （<b>按 name 匹配取 id</b>：v2 按 id 单查存在 404 怪癖，list 匹配最稳）；</li>
 *   <li>{@code POST .../collections/{id}/query}——向量检索，请求
 *       {@code {query_embeddings:[[...]], n_results, include}}，响应为<b>批次包一层</b>的
 *       {@code ids/documents/metadatas/distances} 双层数组（cosine 距离，越小越近）；</li>
 *   <li>{@code POST .../collections/{id}/get}——全量/分页拉取 documents+metadatas（BM25 内存语料源）；</li>
 *   <li>{@code POST .../collections/{id}/upsert}——确定性 id 幂等写入。</li>
 * </ul>
 *
 * <p>错误透出带可行动诊断（沿用入库流水线排障口径）：连接拒绝/超时 → 检查 base-url/安全组/SSH 隧道；
 * 4xx/5xx → 状态码 + 响应体片段。HTTP 异常原样上抛供熔断守卫计数（{@code CircuitBreakerGuard}）。
 */
public class ChromaRestClient {

    private static final int UPSERT_BATCH = 64;
    private static final int GET_PAGE = 500;
    private static final int MAX_DOCS = 1_000_000;

    private final String baseUrl;
    private final String apiKey;
    private final String tenant;
    private final String database;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public ChromaRestClient(String baseUrl, String apiKey, String tenant, String database,
                            RestTemplate restTemplate, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.tenant = tenant;
        this.database = database;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    /** 集合引用（id + 维度；id 由 list 按 name 匹配解析）。 */
    public record CollectionRef(String id, String name, int dimension) { }

    /** 单条检索命中（cosine 距离，相似度 = 1 - distance 由调用方换算）。 */
    public record ChromaHit(String id, String document, Map<String, Object> metadata, double distance) { }

    /** 单条文档（get 拉取，全量语料/BM25 内存源）。 */
    public record ChromaDoc(String id, String document, Map<String, Object> metadata) { }

    /** 存活探测：心跳正常返回 true（供启动日志/健康排查，不阻塞装配）。 */
    public boolean heartbeat() {
        try {
            JsonNode node = mapper.readTree(getForString("/api/v2/heartbeat"));
            return node.has("nanosecond heartbeat");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 按名称解析集合（list 匹配；v2 按 id 单查有 404 怪癖）。
     *
     * @return 集合引用；不存在返回 null（调用方决定降级/报错口径）
     */
    public CollectionRef resolveCollection(String name) {
        String resp = getForString(collectionsBase());
        JsonNode arr = mapper.readTree(resp);
        if (!arr.isArray()) {
            throw new IllegalStateException("Chroma 集合列表响应异常（非数组）：collections=" + snippet(resp));
        }
        for (JsonNode node : arr) {
            if (name.equals(node.path("name").asText(null))) {
                return new CollectionRef(node.path("id").asText(), name, node.path("dimension").asInt(-1));
            }
        }
        return null;
    }

    /**
     * 向量检索：返回按 cosine 距离升序（越近越前）的命中。
     *
     * @param collectionId 集合 id（{@link #resolveCollection} 解析）
     * @param queryVector  查询向量（维度须与集合一致，调用方守卫）
     * @param nResults     召回条数
     */
    public List<ChromaHit> query(String collectionId, float[] queryVector, int nResults) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query_embeddings", List.of(queryVector)); // float[] → [[...]] 双层批次
        body.put("n_results", nResults);
        body.put("include", List.of("documents", "metadatas", "distances"));
        JsonNode root = mapper.readTree(post(collectionsBase() + "/" + collectionId + "/query", body));
        JsonNode ids = root.path("ids");
        JsonNode docs = root.path("documents");
        JsonNode metas = root.path("metadatas");
        JsonNode dists = root.path("distances");
        List<ChromaHit> hits = new ArrayList<>();
        // 批次响应：每字段都是 [[第 1 个查询的 N 条...]]，此处仅 1 个查询向量 → 取内层数组
        hits.addAll(parseHits(ids, docs, metas, dists));
        return hits;
    }

    /**
     * 分页流式遍历全量 documents+metadatas（<b>常驻内存 O(1) 页</b>——Lucene 磁盘索引同步的数据源，
     * 不在 JVM 累积全量语料；调用方逐条消费即弃）。中途异常上抛（调用方决定降级语义）。
     *
     * @param consumer 逐条回调（空白文档已过滤）
     */
    public void forEachDocument(String collectionId, java.util.function.Consumer<ChromaDoc> consumer) {
        int offset = 0;
        while (offset < MAX_DOCS) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("include", List.of("documents", "metadatas"));
            body.put("limit", GET_PAGE);
            body.put("offset", offset);
            JsonNode root = mapper.readTree(post(collectionsBase() + "/" + collectionId + "/get", body));
            JsonNode ids = root.path("ids");
            int size = ids.isArray() ? ids.size() : 0;
            for (int i = 0; i < size; i++) {
                String document = root.path("documents").get(i).asText(null);
                if (document == null || document.isBlank()) {
                    continue; // 空白文档（如纯图片占位缺 OCR）不进索引
                }
                consumer.accept(new ChromaDoc(ids.get(i).asText(), document,
                        toMetadata(root.path("metadatas").get(i))));
            }
            if (size < GET_PAGE) {
                return; // 末页
            }
            offset += GET_PAGE;
        }
    }

    /** 幂等 upsert（确定性 id：同 id 覆盖不重复；分批防超大请求体）。 */
    public void upsert(String collectionId, List<String> ids, List<float[]> embeddings,
                       List<String> documents, List<Map<String, Object>> metadatas) {
        for (int from = 0; from < ids.size(); from += UPSERT_BATCH) {
            int to = Math.min(from + UPSERT_BATCH, ids.size());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ids", ids.subList(from, to));
            body.put("embeddings", embeddings.subList(from, to));
            body.put("documents", documents.subList(from, to));
            body.put("metadatas", metadatas.subList(from, to));
            post(collectionsBase() + "/" + collectionId + "/upsert", body);
        }
    }

    // ---- 内部 ----

    /**
     * 按 metadata.source 精确删除（[[kb-ingest-design]] 知识库换版/下架配套）。
     *
     * <p>where 过滤 {@code {"source": {"$in": [...]}}}（v2 delete 端点，省略 ids 即按 where 全删）。
     *
     * @return Chroma 不返回删除计数，恒 -1（调用方按"尽力删除"口径处理）
     */
    public int deleteBySources(String collectionId, java.util.Collection<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return 0;
        }
        Map<String, Object> body = Map.of(
                "where", Map.of("source", Map.of("$in", List.copyOf(sources))));
        post(collectionsBase() + "/" + collectionId + "/delete", body);
        return -1;
    }

    private List<ChromaHit> parseHits(JsonNode ids, JsonNode docs, JsonNode metas, JsonNode dists) {
        List<ChromaHit> hits = new ArrayList<>();
        // query 响应为双层数组（[查询][命中]）；单查询 → 取第 0 内层
        JsonNode innerIds = firstInner(ids);
        JsonNode innerDocs = firstInner(docs);
        JsonNode innerMetas = firstInner(metas);
        JsonNode innerDists = firstInner(dists);
        int size = innerIds != null ? innerIds.size() : 0;
        for (int i = 0; i < size; i++) {
            String doc = innerDocs != null && innerDocs.get(i) != null && !innerDocs.get(i).isNull()
                    ? innerDocs.get(i).asText(null) : null;
            if (doc == null || doc.isBlank()) {
                continue; // 文档为空（如纯图片占位缺 OCR）不返回
            }
            double dist = innerDists != null && innerDists.get(i) != null
                    ? innerDists.get(i).asDouble(2.0) : 2.0;
            hits.add(new ChromaHit(innerIds.get(i).asText(), doc,
                    toMetadata(innerMetas != null ? innerMetas.get(i) : null), dist));
        }
        return hits;
    }

    private static JsonNode firstInner(JsonNode batched) {
        if (batched == null || !batched.isArray() || batched.isEmpty()) {
            return null;
        }
        JsonNode inner = batched.get(0);
        return inner.isArray() ? inner : batched; // 兼容非包裹形状
    }

    private Map<String, Object> toMetadata(JsonNode node) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.properties().forEach(e -> meta.put(e.getKey(), e.getValue().asText(null)));
        }
        return meta;
    }

    private String collectionsBase() {
        return "/api/v2/tenants/" + tenant + "/databases/" + database + "/collections";
    }

    private String getForString(String path) {
        try {
            return restTemplate.exchange(baseUrl + path, org.springframework.http.HttpMethod.GET,
                    entity(null), String.class).getBody();
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("Chroma 连接失败（" + path + "）：检查 base-url/安全组/SSH 隧道——"
                    + e.getMessage(), e);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("Chroma " + path + " 失败：" + e.getStatusCode()
                    + " body=" + snippet(e.getResponseBodyAsString()), e);
        }
    }

    private String post(String path, Map<String, Object> body) {
        try {
            return restTemplate.postForObject(baseUrl + path, entity(mapper.writeValueAsString(body)), String.class);
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("Chroma 连接失败/超时（" + path + "）：检查 base-url/安全组/SSH 隧道——"
                    + e.getMessage(), e);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("Chroma " + path + " 失败：" + e.getStatusCode()
                    + " body=" + snippet(e.getResponseBodyAsString()), e);
        }
    }

    /** 统一请求头：JSON + 可选 Bearer（GET/POST 一致，服务端开启鉴权时全路由生效）。 */
    private HttpEntity<String> entity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(apiKey)) {
            headers.setBearerAuth(apiKey);
        }
        return new HttpEntity<>(body, headers);
    }

    private static String snippet(String body) {
        if (body == null) {
            return "null";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }
}
