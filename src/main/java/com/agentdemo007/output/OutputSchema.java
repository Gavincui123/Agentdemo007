package com.agentdemo007.output;

import java.util.List;

/**
 * 输出结构描述（第七层·结构化输出校验的 schema 载体）。
 *
 * <p>对话模式（{@code null} schema）下校验宽松通过——/chat 等自由文本回复无需结构校验；
 * 结构化抽取场景（{@link com.agentdemo007.intent.Intent#STRUCTURED_EXTRACTION}）配置
 * {@link #json(List)} 要求输出为合法 JSON 且必填字段齐备。prod 可扩展为完整 JsonSchema 描述。
 *
 * @param requiresJson   是否要求合法 JSON 输出
 * @param requiredFields JSON 必填顶层字段名
 */
public record OutputSchema(boolean requiresJson, List<String> requiredFields) {

    /** 结构化 JSON 输出 schema（必填字段齐备方通过）。 */
    public static OutputSchema json(List<String> requiredFields) {
        return new OutputSchema(true, requiredFields == null ? List.of() : requiredFields);
    }

    /** 自由文本 schema（仅需非空）。 */
    public static OutputSchema text() {
        return new OutputSchema(false, List.of());
    }
}
