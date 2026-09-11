package com.agentdemo007.output;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON Schema 校验器测试（第七层·结构化输出网关的校验内核）。
 *
 * <p>覆盖 §5.7 JsonSchema 校验：null schema→宽松通过（对话模式）；非 JSON 仅需非空；
 * requiresJson→合法 JSON + 必填字段齐备方通过。dev 用 Jackson 3 轻量校验（无新依赖），
 * prod 可替换为完整 JsonSchema 校验库（networknt）薄层覆盖。
 */
class JsonSchemaValidatorTest {

    private final JsonSchemaValidator validator = new JsonSchemaValidator();

    @Test
    void nullSchema_lenientValid() {
        assertThat(validator.validate("任意文本", null).valid()).isTrue();
        assertThat(validator.validate(null, null).valid()).isTrue();
    }

    @Test
    void textSchema_nonBlankValid() {
        OutputSchema schema = OutputSchema.text();
        assertThat(validator.validate("正常回复", schema).valid()).isTrue();
        assertThat(validator.validate("  ", schema).valid()).isFalse();
        assertThat(validator.validate(null, schema).valid()).isFalse();
    }

    @Test
    void jsonSchema_validJsonAllFields_valid() {
        OutputSchema schema = OutputSchema.json(List.of("status", "message"));
        String raw = """
                {"status":"ok","message":"完成"}""";
        assertThat(validator.validate(raw, schema).valid()).isTrue();
    }

    @Test
    void jsonSchema_missingField_invalid() {
        OutputSchema schema = OutputSchema.json(List.of("status", "message"));
        String raw = """
                {"status":"ok"}""";
        ValidationResult result = validator.validate(raw, schema);
        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("message");
    }

    @Test
    void jsonSchema_invalidJson_invalid() {
        OutputSchema schema = OutputSchema.json(List.of("status"));
        ValidationResult result = validator.validate("{not json", schema);
        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("JSON");
    }

    @Test
    void jsonSchema_nullOrBlank_invalid() {
        OutputSchema schema = OutputSchema.json(List.of("status"));
        assertThat(validator.validate(null, schema).valid()).isFalse();
        assertThat(validator.validate("", schema).valid()).isFalse();
    }
}
