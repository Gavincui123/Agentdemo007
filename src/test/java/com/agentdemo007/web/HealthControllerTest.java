package com.agentdemo007.web;

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

class HealthControllerTest extends TestBase {

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

    private Map<String, Object> json(String path) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(base() + path, HttpMethod.GET, null, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        return objectMapper.readValue(resp.getBody(), new TypeReference<Map<String, Object>>() {});
    }

    @Test
    void health_returnsUnifiedResponseWithTraceId() throws Exception {
        Map<String, Object> resp = json("/health");

        assertThat(((Number) resp.get("code")).intValue()).isEqualTo(0);
        assertThat(resp.get("message")).isEqualTo("success");
        assertThat((String) resp.get("traceId")).isNotEmpty();
        assertThat((String) resp.get("timestamp")).isNotEmpty();

        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data.get("status")).isEqualTo("UP");
        assertThat(data.get("service")).isEqualTo("Agentdemo007");
    }

    @Test
    void info_returnsServiceInfo() throws Exception {
        Map<String, Object> resp = json("/info");

        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data.get("name")).isEqualTo("Agentdemo007");
        assertThat(data.get("version")).isEqualTo("0.0.1-SNAPSHOT");
    }
}
