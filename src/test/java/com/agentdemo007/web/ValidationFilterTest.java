package com.agentdemo007.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1：基础数据校验过滤器话术化验收（消息长度上限）。
 * - 请求体超限 → 系统预设话术（HTTP 200 + degraded 标记），短路不进下游；
 * - 请求体在阈值内 → 透传到控制器，200。
 */
class ValidationFilterTest extends TestBase {

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
    void oversizedBody_returnsPhraseAndSkipsDownstream() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        HttpEntity<String> entity = new HttpEntity<>("123456789", headers); // 9 字节 > 阈值 8

        ResponseEntity<String> resp = restTemplate.postForEntity(base() + "/exception/echo", entity, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> body = objectMapper.readValue(resp.getBody(), new TypeReference<>() {
        });
        assertThat(((Number) body.get("code")).intValue()).isEqualTo(0);
        assertThat((String) body.get("traceId")).isNotEmpty();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data.get("degraded")).isEqualTo(true);
        assertThat(data.get("scenario")).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat((String) data.get("reply")).isNotEmpty();
    }

    @Test
    void smallBody_passesThrough() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        HttpEntity<String> entity = new HttpEntity<>("hi", headers); // 2 字节 ≤ 阈值 8

        ResponseEntity<String> resp = restTemplate.postForEntity(base() + "/exception/echo", entity, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo("hi");
    }
}
