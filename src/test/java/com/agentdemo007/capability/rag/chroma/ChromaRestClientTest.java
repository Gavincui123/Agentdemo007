package com.agentdemo007.capability.rag.chroma;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link ChromaRestClient} 单测（MockRest 桩 Chroma v2 REST，端点路由经远程实测确认）。
 *
 * <p>断言：① heartbeat/集合解析（list 按 name 匹配，缺失返 null）；② query 请求体
 * （query_embeddings 双层批次/n_results/include）+ 双层数组响应解析 + cosine 距离透出；
 * ③ get 全量拉取；④ upsert 幂等写；⑤ 非 2xx → 带状态码与响应体片段的异常（可行动诊断）。
 */
class ChromaRestClientTest {

    private static final String BASE = "http://chroma.test:8001";
    private static final String COLLECTIONS = BASE + "/api/v2/tenants/default_tenant/databases/default_database/collections";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final ChromaRestClient client = new ChromaRestClient(BASE, "secret-token",
            "default_tenant", "default_database", restTemplate, JsonMapper.builder().build());

    @BeforeEach
    void bearerHeaderOnAllRequests() {
        // api-key 配置 → 所有请求带 Bearer（无鉴权部署留空则无此头，由 header 可选性决定不在此断言）
    }

    @Test
    void heartbeat_ok() {
        server.expect(requestTo(BASE + "/api/v2/heartbeat"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"nanosecond heartbeat\":1789570446801350564}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.heartbeat()).isTrue();
        server.verify();
    }

    @Test
    void heartbeat_failureReturnsFalse() {
        server.expect(requestTo(BASE + "/api/v2/heartbeat"))
                .andRespond(withServerError());

        assertThat(client.heartbeat()).isFalse();
        server.verify();
    }

    @Test
    void resolveCollection_matchesByName() {
        server.expect(requestTo(COLLECTIONS))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer secret-token"))
                .andRespond(withSuccess("""
                        [{"id":"88a090ee","name":"other","dimension":64},
                         {"id":"c-1","name":"kb_customer_service","dimension":4096}]""",
                        MediaType.APPLICATION_JSON));

        ChromaRestClient.CollectionRef ref = client.resolveCollection("kb_customer_service");

        assertThat(ref).isNotNull();
        assertThat(ref.id()).isEqualTo("c-1");
        assertThat(ref.dimension()).isEqualTo(4096);
        server.verify();
    }

    @Test
    void resolveCollection_missingReturnsNull() {
        server.expect(requestTo(COLLECTIONS))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.resolveCollection("nope")).isNull();
        server.verify();
    }

    @Test
    void query_postsBatchedBody_andParsesBatchedResponse() {
        server.expect(requestTo(COLLECTIONS + "/c-1/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.query_embeddings[0]").isArray())
                .andExpect(jsonPath("$.n_results").value(2))
                .andExpect(jsonPath("$.include[0]").value("documents"))
                .andRespond(withSuccess("""
                        {"ids":[["id1","id2"]],
                         "documents":[["退款政策 / 退款办理窗口","常见问题 / Q:上楼费"]],
                         "metadatas":[[{"source":"01-refund-policy.md","temporal_tag":"CURRENT",
                                        "domain":"after_sale_policy","valid_until":"2026-10-01"},
                                       {"source":"20-faq-services.md"}]],
                         "distances":[[0.2,0.9]]}""",
                        MediaType.APPLICATION_JSON));

        List<ChromaRestClient.ChromaHit> hits = client.query("c-1", new float[]{0.1f, 0.2f}, 2);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).document()).contains("退款办理窗口");
        assertThat(hits.get(0).distance()).isEqualTo(0.2);
        assertThat(hits.get(0).metadata()).containsEntry("temporal_tag", "CURRENT");
        server.verify();
    }

    @Test
    void forEachDocument_streamsPagesWithFiltering() {
        server.expect(requestTo(COLLECTIONS + "/c-1/get"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.limit").value(500))
                .andExpect(jsonPath("$.offset").value(0))
                .andRespond(withSuccess("""
                        {"ids":["a","b","c"],
                         "documents":["文档甲","文档乙"," "],
                         "metadatas":[{"domain":"faq"},{"domain":"after_sale_policy"},{}]}""",
                        MediaType.APPLICATION_JSON));

        List<ChromaRestClient.ChromaDoc> docs = new java.util.ArrayList<>();
        client.forEachDocument("c-1", docs::add);

        assertThat(docs).extracting(ChromaRestClient.ChromaDoc::document)
                .containsExactly("文档甲", "文档乙"); // 空白文档流式过滤，不进索引
        server.verify();
    }

    @Test
    void upsert_postsIdempotentBatch() {
        server.expect(requestTo(COLLECTIONS + "/c-1/upsert"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.ids[0]").value("hash-1"))
                .andExpect(jsonPath("$.embeddings[0]").isArray())
                .andExpect(jsonPath("$.documents[0]").value("文档甲"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.upsert("c-1", List.of("hash-1"), List.of(new float[]{0.5f}),
                List.of("文档甲"), List.of(java.util.Map.of("source", "x")));

        server.verify();
    }

    @Test
    void non2xx_throwsWithActionableDiagnostics() {
        server.expect(requestTo(COLLECTIONS + "/c-1/query"))
                .andRespond(withServerError().body("{\"error\":\"NotFoundError\"}"));

        assertThatThrownBy(() -> client.query("c-1", new float[]{0.1f}, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("NotFoundError");
        server.verify();
    }
}
