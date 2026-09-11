package com.agentdemo007.web;

import com.agentdemo007.common.response.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest extends TestBase {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    {
        restTemplate.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    private Map<String, Object> response(String path, HttpStatus expected) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(base() + path, HttpMethod.GET, null, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(expected);
        assertThat(resp.getBody()).isNotEmpty();
        return objectMapper.readValue(resp.getBody(), new TypeReference<Map<String, Object>>() {});
    }

    @Test
    void businessException_returnsErrorCodeEnvelope() throws Exception {
        Map<String, Object> resp = response("/exception/business", HttpStatus.OK);

        assertThat(((Number) resp.get("code")).intValue()).isEqualTo(ErrorCode.FORBIDDEN.code());
        assertThat(resp.get("message")).isEqualTo("禁止访问");
        assertThat((String) resp.get("traceId")).isNotEmpty();
    }

    @Test
    void sessionCacheException_returns503() throws Exception {
        Map<String, Object> resp = response("/exception/session", HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(((Number) resp.get("code")).intValue()).isEqualTo(ErrorCode.SESSION_CACHE_ERROR.code());
        assertThat((String) resp.get("traceId")).isNotEmpty();
    }

    @Test
    void unexpectedException_returnsInternalErrorWithoutStackTrace() throws Exception {
        Map<String, Object> resp = response("/exception/unexpected", HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(((Number) resp.get("code")).intValue()).isEqualTo(ErrorCode.INTERNAL_ERROR.code());
        assertThat(resp.get("message")).isEqualTo("系统内部错误");
        assertThat(resp.containsKey("stackTrace")).isFalse();
        assertThat((String) resp.get("traceId")).isNotEmpty();
    }

    @Test
    void validationException_returns400() throws Exception {
        // 不带必需参数调用 /exception/validation → 缺参异常 → 400
        Map<String, Object> resp = response("/exception/validation", HttpStatus.BAD_REQUEST);

        assertThat(((Number) resp.get("code")).intValue()).isEqualTo(ErrorCode.BAD_REQUEST.code());
        assertThat((String) resp.get("traceId")).isNotEmpty();
    }
}
