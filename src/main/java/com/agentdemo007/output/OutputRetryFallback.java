package com.agentdemo007.output;

import java.util.Optional;

/**
 * 输出重试/兜底（第七层·Schema 校验失败重试，受最大次数限制）。
 *
 * <p>校验通过→直接返回输出；失败且有重试额度→经 {@link ReAsk} 重问再校验；
 * 重试耗尽或无重问能力（dev {@link ReAsk#none()}）→返回 {@link Optional#empty()}，
 * 交由 {@link StructuredOutputGateway} 兜底话术/默认结构（§5.7 + §5.12 结构化输出行）。
 *
 * <p>重试不涉及故障转移（同模型重提示）；最大次数由 {@code app.output.max-retries} 配置。
 */
public class OutputRetryFallback {

    private final JsonSchemaValidator validator;
    private final int maxRetries;

    public OutputRetryFallback(JsonSchemaValidator validator, int maxRetries) {
        this.validator = validator;
        this.maxRetries = Math.max(0, maxRetries);
    }

    /**
     * 校验并按需重试。
     *
     * @return 合法输出（{@link Optional#of}）；耗尽则空（{@link Optional#empty()}）
     */
    public Optional<String> process(String rawOutput, OutputSchema schema, ReAsk reAsk) {
        String current = rawOutput;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            ValidationResult vr = validator.validate(current, schema);
            if (vr.valid()) {
                return Optional.of(current);
            }
            if (attempt == maxRetries) {
                break; // 重试额度耗尽
            }
            Optional<String> corrected = reAsk.reAsk(current, vr.error());
            if (corrected.isEmpty()) {
                break; // 无重问能力（dev）→不再重试
            }
            current = corrected.get();
        }
        return Optional.empty();
    }
}
