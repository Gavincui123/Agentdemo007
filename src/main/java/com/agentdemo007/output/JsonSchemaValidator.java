package com.agentdemo007.output;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON Schema 校验器（第七层·结构化输出网关的校验内核）。
 *
 * <p>对 LLM 原始输出按 {@link OutputSchema} 校验（§5.7）：
 * <ul>
 *   <li>{@code null} schema → 宽松通过（对话模式，自由文本回复）；</li>
 *   <li>非 JSON schema → 仅需输出非空；</li>
 *   <li>{@link OutputSchema#requiresJson()} → 解析为合法 JSON 且 {@link OutputSchema#requiredFields()}
 *       齐备方通过。</li>
 * </ul>
 * dev 用 Jackson 3 轻量校验（无新依赖）；prod 可替换为完整 JsonSchema 校验库
 * （如 {@code com.networknt:json-schema-validator}）薄层覆盖，校验出口形状（{@link ValidationResult}）不变。
 */
public class JsonSchemaValidator {

    private final ObjectMapper mapper = new ObjectMapper();

    public ValidationResult validate(String rawOutput, OutputSchema schema) {
        if (schema == null) {
            return ValidationResult.ok();
        }
        if (rawOutput == null || rawOutput.isBlank()) {
            return ValidationResult.fail("输出为空");
        }
        if (!schema.requiresJson()) {
            return ValidationResult.ok();
        }
        try {
            JsonNode node = mapper.readTree(rawOutput);
            for (String field : schema.requiredFields()) {
                if (!node.has(field)) {
                    return ValidationResult.fail("缺失必填字段: " + field);
                }
            }
            return ValidationResult.ok();
        } catch (Exception e) {
            return ValidationResult.fail("非合法 JSON: " + e.getMessage());
        }
    }
}
