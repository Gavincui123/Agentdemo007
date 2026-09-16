package com.agentdemo007.capability.business;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * 政策片段（知识库数据 carrier，带 citation·[[business-tools-workflow-dag]] §2.1）。
 *
 * <p>text = 政策正文，source = 知识库来源 citation（"退货政策知识库§3" 等，带来源可追溯）。
 * {@link #toJson()} 产出 {@code {"text":"...","source":"..."}} 供 RAG @Tool 返回（用户钦定
 * "能标识知识库数据的 JSON 对象"）；{@code com.agentdemo007.capability.tool.ToolExecutionStep} 经
 * {@link #fromJson} 拆 text+source 路由进 {@code ragFragments}+{@code ragCitations}。
 * Jackson 3（{@code tools.jackson}，镜像 {@link com.agentdemo007.capability.plan.RouteCandidateParser} 范式：
 * {@code readValue(String)} v3 已移除，故 {@code readTree}→{@code treeToValue}）。
 */
public record PolicyFragment(String text, String source) {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * 序列化为 {@code {"text":"...","source":"..."}} JSON（RAG @Tool 返回此，
     * {@code ToolExecutionStep} 经 {@link #fromJson} 拆 text+source 路由进 ragFragments+ragCitations）。
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            // 兜底手拼（mock 政策文本可控；真 RAG 接入后由 HybridRetriever 直产 fragment）
            return "{\"text\":\"" + text + "\",\"source\":\"" + source + "\"}";
        }
    }

    /**
     * 反序列化 {@code {"text","source"}} JSON；null/blank/非合法 → empty（②每步降级，不阻塞主链路）。
     */
    public static Optional<PolicyFragment> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return Optional.ofNullable(MAPPER.treeToValue(node, PolicyFragment.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
