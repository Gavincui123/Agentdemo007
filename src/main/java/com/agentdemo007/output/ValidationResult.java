package com.agentdemo007.output;

/**
 * Schema 校验结果（第七层·{@link JsonSchemaValidator} 的产出）。
 *
 * @param valid  是否通过
 * @param error  失败原因（通过时为 {@code null}）
 */
public record ValidationResult(boolean valid, String error) {

    public static ValidationResult ok() {
        return new ValidationResult(true, null);
    }

    public static ValidationResult fail(String error) {
        return new ValidationResult(false, error);
    }
}
