package com.agentdemo007.capability.rag.chroma;

import com.agentdemo007.capability.rag.CircuitBreakerGuard;
import com.agentdemo007.capability.rag.EmbeddingService;
import com.agentdemo007.capability.rag.RagFragment;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link ChromaVectorStore} 单测（MockRest 桩 Chroma v2）。
 *
 * <p>断言：① metadata 契约映射（source/updated→timestamp/valid_until→validUntil/temporal_tag→temporalTag/
 * domain→domain）+ cosine 距离→相似度 score=1−distance + cosineScored=true；② 维度守卫（64 维 hash vs
 * 4096 维集合 → 带行动指引异常）；③ 集合缺失 → 带入库流水线指引异常；④ chunkId 确定性（幂等 upsert）；
 * ⑤ 日期格式解析（Instant 全格式 / yyyy-MM-dd）。
 */
class ChromaVectorStoreTest {

    private static final String BASE = "http://chroma.test:8001";
    private static final String COLLECTIONS =
            BASE + "/api/v2/tenants/default_tenant/databases/default_database/collections";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final ChromaRestClient client = new ChromaRestClient(BASE, "",
            "default_tenant", "default_database", restTemplate, JsonMapper.builder().build());
    private final ChromaProperties props = new ChromaProperties();

    private ChromaVectorStore newStore(EmbeddingService embedding) {
        return new ChromaVectorStore(client, props, embedding, new CircuitBreakerGuard("chroma", 60_000, 30_000));
    }

    private void stubCollectionResolution() {
        server.expect(requestTo(COLLECTIONS))
                .andRespond(withSuccess("""
                        [{"id":"c-1","name":"kb_customer_service","dimension":4096}]""",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void search_mapsMetadataContract_andCosineDistance() {
        stubCollectionResolution();
        server.expect(requestTo(COLLECTIONS + "/c-1/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.n_results").value(3))
                .andRespond(withSuccess("""
                        {"ids":[["id1"]],
                         "documents":[["退款办理窗口：30 天内可发起退款申请"]],
                         "metadatas":[[{"source":"01-refund-policy.md","temporal_tag":"CURRENT",
                                        "domain":"after_sale_policy","updated":"2026-09-15"}]],
                         "distances":[[0.25]]}""",
                        MediaType.APPLICATION_JSON));

        ChromaVectorStore store = newStore(text -> new float[4096]);
        List<RagFragment> hits = store.search(new float[4096], 3);

        assertThat(hits).hasSize(1);
        RagFragment f = hits.get(0);
        assertThat(f.score()).isEqualTo(0.75, org.assertj.core.data.Offset.offset(1e-9)); // 1 - 0.25
        assertThat(f.source()).isEqualTo("01-refund-policy.md");
        assertThat(f.temporalTag()).isEqualTo("CURRENT");
        assertThat(f.domain()).isEqualTo("after_sale_policy");
        assertThat(f.validUntil()).isNull();
        assertThat(f.timestamp()).isEqualTo(Instant.parse("2026-09-15T00:00:00Z")); // 日期按 UTC 当日起算
        assertThat(f.cosineScored()).isTrue();   // 余弦口径 → 可过置信度终闸
        assertThat(f.relevance()).isNull();      // 未重排
        server.verify();
    }

    @Test
    void search_dimensionMismatch_throwsWithActionableHint() {
        stubCollectionResolution();

        ChromaVectorStore store = newStore(text -> new float[64]); // dev hash 64 维
        assertThatThrownBy(() -> store.search(new float[64], 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMBEDDING_ENABLED")
                .hasMessageContaining("Qwen/Qwen3-Embedding-8B");
        server.verify();
    }

    @Test
    void search_collectionMissing_throwsWithIngestHint() {
        server.expect(requestTo(COLLECTIONS)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        ChromaVectorStore store = newStore(text -> new float[64]);
        assertThatThrownBy(() -> store.search(new float[64], 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("入库流水线");
        server.verify();
    }

    @Test
    void search_zeroOrEmptyVector_returnsEmpty() {
        ChromaVectorStore store = newStore(text -> new float[64]);
        assertThat(store.search(new float[0], 3)).isEmpty();
        assertThat(store.search(null, 3)).isEmpty();
    }

    @Test
    void index_upsertsWithDeterministicChunkId() {
        stubCollectionResolution();
        server.expect(requestTo(COLLECTIONS + "/c-1/upsert"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.ids[0]").value(ChromaVectorStore.chunkId("退款流程", "kb-refund")))
                .andExpect(jsonPath("$.documents[0]").value("退款流程"))
                .andExpect(jsonPath("$.metadatas[0].source").value("kb-refund"))
                .andExpect(jsonPath("$.metadatas[0].temporal_tag").value("HISTORICAL"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        ChromaVectorStore store = newStore(text -> new float[]{0.1f, 0.2f});
        store.index(List.of(new RagFragment("退款流程", 0.0, "kb-refund", null,
                Instant.parse("2024-06-30T00:00:00Z"), "HISTORICAL")));

        server.verify();
    }

    @Test
    void chunkId_deterministicAndSourceSensitive() {
        String a = ChromaVectorStore.chunkId("同文不同源", "src-a");
        String b = ChromaVectorStore.chunkId("同文不同源", "src-b");
        assertThat(a).isEqualTo(ChromaVectorStore.chunkId("同文不同源", "src-a")); // 幂等
        assertThat(a).isNotEqualTo(b);                                             // 区分来源
        assertThat(a).hasSize(32);
    }

    @Test
    void parseInstant_supportsInstantAndDateOnlyFormats() {
        assertThat(ChromaVectorStore.parseInstant("2026-09-15T08:00:00Z"))
                .isEqualTo(Instant.parse("2026-09-15T08:00:00Z"));
        assertThat(ChromaVectorStore.parseInstant("2026-09-15"))
                .isEqualTo(Instant.parse("2026-09-15T00:00:00Z"));
        assertThat(ChromaVectorStore.parseInstant("not-a-date")).isNull();
        assertThat(ChromaVectorStore.parseInstant(null)).isNull();
    }
}
