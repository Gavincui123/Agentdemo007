package com.agentdemo007.capability.tool;

import java.util.List;

/**
 * 参数校验结果（第四层·{@link SchemaValidator} 产出）。
 *
 * <p>{@code ok=true} 通过；{@code ok=false} 携带错误明细交 {@code ToolErrorFeedback} 反馈 LLM 自纠正。
 */
public record ValidationResult(boolean ok, List<String> errors) {

    private static final ValidationResult VALID = new ValidationResult(true, List.of());

    public static ValidationResult valid() {
        return VALID;
    }

    public static ValidationResult invalid(List<String> errors) {
        return new ValidationResult(false, List.copyOf(errors));
    }
}
