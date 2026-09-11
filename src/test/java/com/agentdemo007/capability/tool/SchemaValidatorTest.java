package com.agentdemo007.capability.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具参数 Schema 校验器测试（第四层·JSON Schema 校验，手写无外部依赖）。
 *
 * <p>验收（Phase 9）：参数校验失败有明确错误反馈。校验：必填缺失 / 类型不符 / 可选缺失放行 / 额外参数忽略。
 */
class SchemaValidatorTest {

    private final SchemaValidator validator = new SchemaValidator();

    private static ToolDefinition arithmeticTool() {
        return new ToolDefinition(
                "arithmetic", "四则运算",
                List.of(new ParamSpec("expression", String.class, true)),
                input -> java.util.Optional.of(Map.of("expression", input)),
                args -> "r");
    }

    @Test
    void validArgs_passes() {
        ValidationResult vr = validator.validate(Map.of("expression", "1+2"), arithmeticTool());
        assertThat(vr.ok()).isTrue();
        assertThat(vr.errors()).isEmpty();
    }

    @Test
    void missingRequired_fails() {
        ValidationResult vr = validator.validate(Map.of(), arithmeticTool());
        assertThat(vr.ok()).isFalse();
        assertThat(vr.errors()).anyMatch(e -> e.contains("expression"));
    }

    @Test
    void wrongType_fails() {
        // 期望 String，传入 Integer
        ValidationResult vr = validator.validate(Map.of("expression", 123), arithmeticTool());
        assertThat(vr.ok()).isFalse();
        assertThat(vr.errors()).anyMatch(e -> e.contains("expression"));
    }

    @Test
    void nullArgs_fails() {
        ValidationResult vr = validator.validate(null, arithmeticTool());
        assertThat(vr.ok()).isFalse();
    }

    @Test
    void optionalMissing_passes() {
        ToolDefinition tool = new ToolDefinition(
                "t", "d",
                List.of(new ParamSpec("opt", String.class, false)),
                input -> java.util.Optional.empty(), args -> "r");
        ValidationResult vr = validator.validate(Map.of(), tool);
        assertThat(vr.ok()).isTrue();
    }

    @Test
    void extraArgs_ignoredLenient() {
        // 额外参数不报错（宽松校验，不阻断工具执行）
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("expression", "1+2");
        args.put("extra", "x");
        ValidationResult vr = validator.validate(args, arithmeticTool());
        assertThat(vr.ok()).isTrue();
    }

    @Test
    void nullValue_forRequired_fails() {
        // 必填参数显式为 null 视为缺失
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("expression", null);
        ValidationResult vr = validator.validate(args, arithmeticTool());
        assertThat(vr.ok()).isFalse();
    }
}
