package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link SiliconFlowReranker} 单测（最小路径·MockRest 桩 SiliconFlow /v1/rerank）。
 *
 * <p>断言：① POST {base}/rerank + Bearer + 请求体 model/query/documents/top_n/return_documents=false；
 * ② 解析 results[].index+relevance_score 按相关性降序重排；③ api-key 空→抛；④ 非 2xx→抛（主备前提）；
 * ⑤ 空查询保留召回序、不调 API；⑥ 空候选→空。
 */
class SiliconFlowRerankerTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final SiliconFlowReranker r = new SiliconFlowReranker(
            "https://api.test/v1", "test-key", "Qwen/Qwen3-Reranker-8B", restTemplate, JsonMapper.builder().build());

    private static RagFragment frag(String t) {
        return new RagFragment(t, 0.0, "src");
    }

    @Test
    void rerank_postsToRerankAndReordersByScore() {
        server.expect(requestTo("https://api.test/v1/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("Qwen/Qwen3-Reranker-8B"))
                .andExpect(jsonPath("$.query").value("退款"))
                .andExpect(jsonPath("$.documents[0]").value("A"))
                .andExpect(jsonPath("$.documents[1]").value("B"))
                .andExpect(jsonPath("$.top_n").value(2))
                .andExpect(jsonPath("$.return_documents").value(false))
                .andRespond(withSuccess(
                        "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.3}]}",
                        MediaType.APPLICATION_JSON));

        List<RagFragment> out = r.rerank("退款", List.of(frag("A"), frag("B")));

        assertThat(out).extracting(RagFragment::text).containsExactly("B", "A"); // 分高者先行
        // relevance 分字段：检索置信度 score 不被覆写（终闸双判据：被重排的看 relevance）
        assertThat(out.get(0).relevance()).isEqualTo(0.9);
        assertThat(out.get(1).relevance()).isEqualTo(0.3);
        assertThat(out.get(0).score()).isEqualTo(0.0);
        server.verify();
    }

    @Test
    void blankApiKey_throws() {
        SiliconFlowReranker noKey = new SiliconFlowReranker(
                "https://api.test/v1", "", "X", restTemplate, JsonMapper.builder().build());
        assertThrows(RuntimeException.class, () -> noKey.rerank("q", List.of(frag("A"))));
    }

    @Test
    void non2xx_throws() {
        server.expect(requestTo("https://api.test/v1/rerank"))
                .andRespond(withServerError());
        assertThrows(RuntimeException.class, () -> r.rerank("q", List.of(frag("A"))));
    }

    @Test
    void blankQuery_preservesCandidateOrder_noApiCall() {
        List<RagFragment> cands = List.of(frag("A"), frag("B"), frag("C"));
        assertThat(r.rerank("", cands)).extracting(RagFragment::text).containsExactly("A", "B", "C");
        server.verify(); // 无请求发出
    }

    @Test
    void emptyCandidates_returnsEmpty() {
        assertThat(r.rerank("q", List.of())).isEmpty();
    }
}
