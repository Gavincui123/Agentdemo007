package com.agentdemo007.intent;

import com.agentdemo007.capability.rag.CircuitBreakerGuard;
import com.agentdemo007.observability.AgentMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LingDtDecisionClient} 单测（2026-10-24 接入：意图识别决策型大模型通道）。
 *
 * <p>验四件事：<b>请求组装</b>（System One 形态：state=history+latest_message、questions.intent
 * choice 型、criteria=MODEL_CANDIDATES 单一真源、URL 带 model 路由名、Bearer 认证）；
 * <b>响应校验</b>（文档 §4.2 顺序：errorCode≠0 / choice 越界 / confidence 越界 / HTTP 错误各自拒绝）；
 * <b>熔断快速短路</b>（单次失败开断，后续调用零 HTTP 直接抛）；<b>半开自动恢复</b>（冷却期满探测
 * 成功即切回，失败降级语义由 {@code IntentRecognizerImplTest} 钉）。
 */
class LingDtDecisionClientTest {

    private final RestTemplate rt = mock(RestTemplate.class);
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private static LingDtProperties props() {
        LingDtProperties p = new LingDtProperties();
        p.setEnabled(true);
        p.setEndpoint("http://lingdt.test/v1/systemone");
        p.setModel("lingdt-decision-1.0-27b");
        p.setApiKey("test-key");
        return p;
    }

    private LingDtDecisionClient client() {
        return new LingDtDecisionClient(props(), rt, mapper, AgentMetrics.NO_OP);
    }

    /** 文档 §1.2 实测响应同构。 */
    private static String okResponse(String choice, double confidence) {
        return """
                {"model":"lingdt","answers":{"intent":{"type":"choice","choice":"%s",
                "probabilities":{"%s":0.99},"confidence":%s}},"usage":{"input_tokens":96,"output_tokens":0},"errorCode":0}
                """.formatted(choice, choice, confidence);
    }

    // ---- 请求组装 ----

    @Test
    void classifies_buildsSystemOneRequest() {
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(okResponse("CHIT_CHAT", 0.9984));
        LingDtDecisionClient client = client();

        LingDtDecisionClient.Decision d = client.classify("今天天气真好",
                List.of(new com.agentdemo007.session.model.ChatMessage.User("你好")));

        assertThat(d.intent()).isEqualTo(Intent.CHIT_CHAT);
        assertThat(d.confidence()).isEqualTo(0.9984); // 真实置信度透传（替代写死 0.7）
        org.mockito.ArgumentCaptor<String> url = org.mockito.ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<HttpEntity<String>> entity =
                org.mockito.ArgumentCaptor.forClass((Class) HttpEntity.class);
        verify(rt).postForObject(url.capture(), entity.capture(), eq(String.class));
        assertThat(url.getValue()).isEqualTo("http://lingdt.test/v1/systemone"); // model 在 body，URL 无 query
        HttpHeaders headers = entity.getValue().getHeaders();
        assertThat(headers.get(HttpHeaders.AUTHORIZATION)).containsExactly("Bearer test-key");
        String body = entity.getValue().getBody();
        assertThat(body).contains("\"model\":\"lingdt-decision-1.0-27b\""); // 路由名在请求体顶层（实测网关口径）
        assertThat(body).contains("\"latest_message\":\"今天天气真好\"");
        assertThat(body).contains("\"type\":\"choice\"");
        assertThat(body).contains("CHIT_CHAT").contains("REASONING").contains("LONG_CONTEXT")
                .contains("STRUCTURED_EXTRACTION").contains("OTHER"); // criteria=MODEL_CANDIDATES 全集
        assertThat(body).contains("[用户] 你好"); // state.history 近期会话背景
        assertThat(body).doesNotContain("TRANSFER_TO_HUMAN").doesNotContain("INJECTION"); // 规则层独占不进候选
    }

    // ---- 响应校验（文档 §4.2 顺序）----

    @Test
    void businessErrorCode_rejected() {
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"errorCode\":1,\"errorDesc\":\"invalid_request:questions_empty\"}");

        assertThatThrownBy(() -> client().classify("q", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("errorCode=1").hasMessageContaining("questions_empty");
    }

    @Test
    void choiceOutsideCandidates_rejected() {
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(okResponse("TRANSFER_TO_HUMAN", 0.9));

        assertThatThrownBy(() -> client().classify("q", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("choice 越界");
    }

    @Test
    void confidenceOutOfRange_rejected() {
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(okResponse("OTHER", 1.5));

        assertThatThrownBy(() -> client().classify("q", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confidence 越界");
    }

    @Test
    void confidenceNaN_rejected() {
        // confidence 以字符串 "NaN" 回来：asDouble 转换失败落默认值 -1 → 越界拒绝（实测口径；
        // 垃圾置信度无论以何种形态到达都进不了结果，2026-10-24 review 补盲区）
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"errorCode\":0,\"answers\":{\"intent\":{\"type\":\"choice\","
                        + "\"choice\":\"OTHER\",\"confidence\":\"NaN\"}}}");

        assertThatThrownBy(() -> client().classify("q", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confidence 越界");
    }

    @Test
    void choiceMissing_rejected() {
        // type=choice 但 choice 字段缺失 → asText(null) → 候选集匹配 null → 拒绝（≠「越界值」分支）
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"errorCode\":0,\"answers\":{\"intent\":{\"type\":\"choice\",\"confidence\":0.9}}}");

        assertThatThrownBy(() -> client().classify("q", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("choice 越界").hasMessageContaining("null");
    }

    @Test
    void answersMissing_rejected() {
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"errorCode\":0}");

        assertThatThrownBy(() -> client().classify("q", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("答案缺失或类型不符");
    }

    @Test
    void answersIntentAsArray_rejected() {
        // answers.intent 结构不符（数组）→ type 校验拒绝
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"errorCode\":0,\"answers\":{\"intent\":[]}}");

        assertThatThrownBy(() -> client().classify("q", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("答案缺失或类型不符");
    }

    @Test
    void choiceCaseInsensitive_accepted() {
        // 大小写不敏感匹配是接受语义的一部分（文档容差口径），钉住防误改成全等
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(okResponse("other", 0.9));

        LingDtDecisionClient.Decision d = client().classify("q", List.of());

        assertThat(d.intent()).isEqualTo(Intent.OTHER);
    }

    @Test
    void httpError_wrapped() {
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        assertThatThrownBy(() -> client().classify("q", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LingDT HTTP").hasMessageContaining("401");
    }

    @Test
    void connectFailure_wrapped() {
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> client().classify("q", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("连接失败/超时");
    }

    @Test
    void unparseableBody_rejected() {
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("<html>gateway error</html>");

        assertThatThrownBy(() -> client().classify("q", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("响应不可解析");
    }

    // ---- 熔断快速短路 + 半开自动恢复 ----

    @Test
    void breakerOpensAfterFirstFailure_subsequentCallsSkipHttp() {
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"));
        // 冷却期拉长（30s）：本用例只验「开断期间零 HTTP」
        LingDtDecisionClient client = new LingDtDecisionClient(props(), rt, mapper,
                AgentMetrics.NO_OP, new CircuitBreakerGuard("lingdt-test", 60_000, 30_000));

        assertThatThrownBy(() -> client.classify("q1", List.of())).hasMessageContaining("连接失败/超时");
        assertThatThrownBy(() -> client.classify("q2", List.of()))
                .hasMessageContaining("熔断开启中"); // 快速短路，不再吃读超时

        verify(rt, times(1)).postForObject(anyString(), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void halfOpenProbeAfterCooldown_recoversOnSuccess() throws InterruptedException {
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(okResponse("OTHER", 0.93));
        // 冷却 50ms：失败开断 → 冷却期满半开放行探测 → 成功关闭断路器（自动切回）
        LingDtDecisionClient client = new LingDtDecisionClient(props(), rt, mapper,
                AgentMetrics.NO_OP, new CircuitBreakerGuard("lingdt-test", 60_000, 50));

        assertThatThrownBy(() -> client.classify("q1", List.of())).hasMessageContaining("连接失败/超时");
        Thread.sleep(120); // 过冷却期（sleep 只会超不会欠，50ms 冷却必已期满——无 flake 风险）
        LingDtDecisionClient.Decision d = client.classify("q2", List.of());

        assertThat(d.intent()).isEqualTo(Intent.OTHER); // 半开探测成功，自动恢复
        assertThat(d.confidence()).isEqualTo(0.93);
    }

    @Test
    void openCircuitCountedAsModelFailure() {
        // 指标在 classify 收口（2026-10-24 review 收口）：开路零等待快速失败同样计败——
        // 否则 LingDT 全挂期间 /obs 全局成功率反而「正常」
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        when(rt.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"));
        LingDtDecisionClient client = new LingDtDecisionClient(props(), rt, mapper,
                new AgentMetrics(meterRegistry), new CircuitBreakerGuard("lingdt-test", 60_000, 30_000));

        assertThatThrownBy(() -> client.classify("q1", List.of())).hasMessageContaining("连接失败/超时"); // 真失败
        assertThatThrownBy(() -> client.classify("q2", List.of())).hasMessageContaining("熔断开启中"); // 开路快速失败

        assertThat(meterRegistry.get("agent.gateway.calls").tag("success", "false").counter().count())
                .isEqualTo(2.0);
    }
}
