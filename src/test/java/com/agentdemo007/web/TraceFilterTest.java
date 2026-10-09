package com.agentdemo007.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1：链路追踪过滤器验收。
 * - 响应携带 X-Trace-Id 头；
 * - 头与 body.traceId 一致；
 * - 入站 X-Trace-Id 头被透传复用。
 */
class TraceFilterTest extends TestBase {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    {
        restTemplate.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
    }

    @Test
    void responseHasTraceIdHeader() {
        ResponseEntity<String> resp = restTemplate.exchange(base() + "/health", HttpMethod.GET, null, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getFirst("X-Trace-Id")).isNotEmpty();
    }

    @Test
    void traceIdHeaderMatchesBody() throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(base() + "/health", HttpMethod.GET, null, String.class);
        String headerTraceId = resp.getHeaders().getFirst("X-Trace-Id");
        Map<String, Object> body = objectMapper.readValue(resp.getBody(), new TypeReference<>() {
        });
        assertThat(headerTraceId).isEqualTo(body.get("traceId"));
    }

    @Test
    void incomingTraceIdHeaderIsPropagated() throws Exception {
        // Phase 23 契约收紧：入站 X-Trace-Id 须为 32-hex（W3C traceparent 要求同格式，OTel 的
        // MdcBackedIdGenerator 以 MDC 值充当 OTel traceId）——合法值透传，非法值重新生成
        String incoming = "3786e975188ef2b0e73c6377a02761a1";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", incoming);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> resp = restTemplate.exchange(base() + "/health", HttpMethod.GET, entity, String.class);
        assertThat(resp.getHeaders().getFirst("X-Trace-Id")).isEqualTo(incoming);

        Map<String, Object> body = objectMapper.readValue(resp.getBody(), new TypeReference<>() {
        });
        assertThat(body.get("traceId")).isEqualTo(incoming);
    }

    @Test
    void incomingNonHexTraceIdIsRegenerated() {
        // 非法格式（非 32-hex）→ 视为未携带，重新生成（归一化口径见 TraceId.normalize）
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", "abc-123-trace");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> resp = restTemplate.exchange(base() + "/health", HttpMethod.GET, entity, String.class);
        String traceId = resp.getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).isNotEqualTo("abc-123-trace").hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void incomingUppercaseTraceIdIsNormalized() {
        // 大写合法 hex → 归一为小写透传（W3C 小写口径）
        String incoming = "3786E975188EF2B0E73C6377A02761A1";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", incoming);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> resp = restTemplate.exchange(base() + "/health", HttpMethod.GET, entity, String.class);
        assertThat(resp.getHeaders().getFirst("X-Trace-Id")).isEqualTo(incoming.toLowerCase());
    }
}
