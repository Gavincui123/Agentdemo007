package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link SiliconFlowEmbeddingService} 单测（最小路径·MockRest 桩 SiliconFlow /v1/embeddings）。
 *
 * <p>断言：① POST {base}/embeddings + Bearer 鉴权 + 请求体 model/input/encoding_format=float；
 * ② 解析 data[0].embedding → float[]；③ api-key 空→抛（主备/降级前提）；④ 非 2xx→抛（主备切换前提）；
 * ⑤ 空文本→零向量、不调 API（契约同 HashEmbeddingService，InMemoryVectorStore 零范数→空召回）。
 */
class SiliconFlowEmbeddingServiceTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final SiliconFlowEmbeddingService svc = new SiliconFlowEmbeddingService(
            "https://api.test/v1", "test-key", "Qwen/Qwen3-Embedding-8B", restTemplate, JsonMapper.builder().build());

    @Test
    void embed_postsToEmbeddingsAndParsesVector() {
        server.expect(requestTo("https://api.test/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("Qwen/Qwen3-Embedding-8B"))
                .andExpect(jsonPath("$.input").value("hello"))
                .andExpect(jsonPath("$.encoding_format").value("float"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.25,0.5,0.75],\"index\":0}]}",
                        MediaType.APPLICATION_JSON));

        float[] vec = svc.embed("hello");

        assertThat(vec).containsExactly(0.25f, 0.5f, 0.75f);
        server.verify();
    }

    @Test
    void blankApiKey_throws() {
        SiliconFlowEmbeddingService noKey = new SiliconFlowEmbeddingService(
                "https://api.test/v1", "", "Qwen/X", restTemplate, JsonMapper.builder().build());
        assertThrows(RuntimeException.class, () -> noKey.embed("hello"));
    }

    @Test
    void non2xx_throws() {
        server.expect(requestTo("https://api.test/v1/embeddings"))
                .andRespond(withServerError());
        assertThrows(RuntimeException.class, () -> svc.embed("hello"));
    }

    @Test
    void blankText_returnsEmptyVector_noApiCall() {
        assertThat(svc.embed("")).isEmpty();
        assertThat(svc.embed(null)).isEmpty();
        server.verify(); // 无请求发出（空文本不调 API）
    }
}
