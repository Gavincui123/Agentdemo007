package com.agentdemo007.intent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 意图识别·决策型大模型 LingDT-Decision 配置（2026-10-24 接入，蚂蚁数科 DTMaaS System One）。
 *
 * <p>LingDT-Decision 是<b>专有决策 API</b>（{@code POST {endpoint}}，model 路由名在<b>请求体顶层</b>
 * ——URL query 不认，网关报 missing_model；state + questions/criteria 结构，choice 型返回
 * {@code choice + probabilities + confidence}），非 OpenAI 兼容——
 * 不进 {@code llm.providers} 体系，仅意图识别 LLM 分类层经 {@link LingDtDecisionClient} 专用出站。
 *
 * <p><b>降级兼容</b>：{@code enabled=false}（默认）时 client 不装配，意图识别维持原通用小模型
 * 通道（行为零变化）；启用后 LingDT 不可用（HTTP/超时/业务码/解析失败/熔断开路）时<b>同轮自动
 * 回退</b>通用小模型，熔断冷却后半开探测自动切回（详见 {@link LingDtDecisionClient}）。
 *
 * <p>接入段三要素（endpoint/model/api-key）由 Nacos dataId 注入，密钥经 env {@code ${LINGDT_API_KEY}}
 * 占位解析（对齐 SF_KEY/ALIYUN_KEY 惯例，密钥不落 Nacos 明文）。
 */
@ConfigurationProperties(prefix = "lingdt")
public class LingDtProperties {

    /** 总开关：false（默认）不装配 client，意图识别走原通用小模型通道。 */
    private boolean enabled = false;

    /** System One 决策端点（含 {@code /v1/systemone} 路径，如 {@code https://<MAAS_GATEWAY>/v1/systemone}）。 */
    private String endpoint;

    /** 已授权的模型路由名（请求体顶层 model 字段，如 lingdt-decision-1.0；4B/27B 版本不同路由名）。 */
    private String model;

    /** API Key（Bearer 认证；值经 env {@code LINGDT_API_KEY} 注入）。 */
    private String apiKey;

    /** 连接超时 ms（对齐 embedding/chroma 惯例 5s）。 */
    private long connectTimeoutMs = 5_000;

    /** 读超时 ms（对齐 embedding/chroma 惯例 15s；决策调用实测秒级，15s=充分余量）。 */
    private long readTimeoutMs = 15_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
