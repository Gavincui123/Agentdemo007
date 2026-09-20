package com.agentdemo007.capability.business;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Optional;

/**
 * 政策片段（知识库数据 carrier，带 citation·[[business-tools-workflow-dag]] §2.1）。
 *
 * <p>text = 政策正文，source = 知识库来源 citation（"退货政策知识库§3" 等，带来源可追溯）。
 * {@code hit} = 是否真实命中政策条目（[[refusal-design]] 拒答配套）：<b>false 表示政策库无此条目</b>
 * （兜底文案），{@code ToolExecutionStep} 据此把兜底文案路由进 {@code context.toolDataMisses}
 * （「工具无数据」块）而<b>不再混入 ragFragments 冒充政策正文</b>——工具连通但没数据时，
 * 兜底话术由终答 LLM 在"未查到"框定下转述，而非被当成高置信知识。
 * {@link #toJson()} 产出 {@code {"text":"...","source":"...","hit":true}} 供 RAG @Tool 返回；
 * {@code com.agentdemo007.capability.tool.ToolExecutionStep} 经 {@link #fromJson} 拆字段路由。
 * Jackson 3（{@code tools.jackson}，镜像 {@link com.agentdemo007.capability.plan.RouteCandidateParser} 范式：
 * {@code readValue(String)} v3 已移除，故 {@code readTree}→手动取字段）。
 *
 * @param text   政策正文（hit=false 时为兜底话术）
 * @param source 知识库来源 citation（hit=false 时为"兜底政策"）
 * @param hit    是否真实命中政策条目；false=未命中（text 为兜底话术）
 */
public record PolicyFragment(String text, String source, boolean hit) {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** 兼容构造：真实命中（hit=true）——既有"查到政策"调用方零改动。 */
    public PolicyFragment(String text, String source) {
        this(text, source, true);
    }

    /**
     * 序列化为 {@code {"text","source","hit"}} JSON（RAG @Tool 返回此，
     * {@code ToolExecutionStep} 经 {@link #fromJson} 拆解路由）。
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(Map.of("text", text, "source", source, "hit", hit));
        } catch (Exception e) {
            // 兜底手拼（mock 政策文本可控；真 RAG 接入后由 HybridRetriever 直产 fragment）
            return "{\"text\":\"" + text + "\",\"source\":\"" + source + "\",\"hit\":" + hit + "}";
        }
    }

    /**
     * 反序列化 {@code {"text","source","hit"}} JSON；null/blank/非合法 → empty（②每步降级，不阻塞主链路）。
     * 旧格式（无 hit 字段）按命中处理（hit=true）——历史 JSON 不因新字段误判为未命中。
     */
    public static Optional<PolicyFragment> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            String text = node.path("text").asText(null);
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            String source = node.path("source").asText("");
            boolean hit = node.path("hit").asBoolean(true);
            return Optional.of(new PolicyFragment(text, source, hit));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
