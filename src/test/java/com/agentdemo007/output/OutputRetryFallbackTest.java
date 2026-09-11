package com.agentdemo007.output;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 输出重试/兜底测试（第七层·Schema 校验失败重试，受最大次数限制）。
 *
 * <p>覆盖 §5.7 "失败重试/兜底" + §5.12 结构化输出行"Schema 校验耗尽→兜底"：
 * 校验通过→直接返回；失败且有重试额度→经 {@link ReAsk} 重问再校验；重试耗尽或无重问能力→空（交由兜底）。
 */
class OutputRetryFallbackTest {

    private final JsonSchemaValidator validator = new JsonSchemaValidator();
    private final OutputSchema schema = OutputSchema.json(List.of("status"));

    /** 按序返回预设修正输出的 ReAsk 桩。 */
    private static class QueuedReAsk implements ReAsk {
        final Queue<String> queue = new ArrayDeque<>();
        int calls = 0;

        QueuedReAsk(String... outputs) {
            for (String o : outputs) queue.add(o);
        }

        @Override
        public Optional<String> reAsk(String lastOutput, String error) {
            calls++;
            return Optional.ofNullable(queue.poll());
        }
    }

    @Test
    void validOnFirstTry_noReAsk() {
        QueuedReAsk reAsk = new QueuedReAsk();
        OutputRetryFallback fallback = new OutputRetryFallback(validator, 2);

        Optional<String> result = fallback.process("""
                {"status":"ok"}""", schema, reAsk);

        assertThat(result).contains("""
                {"status":"ok"}""".strip());
        assertThat(reAsk.calls).isZero();
    }

    @Test
    void invalidThenReAskCorrected_returnsCorrected() {
        QueuedReAsk reAsk = new QueuedReAsk("""
                {"status":"ok"}"""); // 第一次重问即修正
        OutputRetryFallback fallback = new OutputRetryFallback(validator, 2);

        Optional<String> result = fallback.process("{bad", schema, reAsk);

        assertThat(result).contains("""
                {"status":"ok"}""".strip());
        assertThat(reAsk.calls).isEqualTo(1);
    }

    @Test
    void exhaustedRetries_returnsEmpty() {
        // 重问始终返回非法 JSON，重试 2 次后耗尽
        QueuedReAsk reAsk = new QueuedReAsk("still bad", "still bad 2");
        OutputRetryFallback fallback = new OutputRetryFallback(validator, 2);

        Optional<String> result = fallback.process("{bad", schema, reAsk);

        assertThat(result).isEmpty();
        assertThat(reAsk.calls).isEqualTo(2);
    }

    @Test
    void devReAskNone_returnsEmptyImmediately() {
        // dev: ReAsk.none() 无重问能力→失败即空（不重试）
        OutputRetryFallback fallback = new OutputRetryFallback(validator, 3);

        Optional<String> result = fallback.process("{bad", schema, ReAsk.none());

        assertThat(result).isEmpty();
    }

    @Test
    void zeroMaxRetries_invalidReturnsEmpty() {
        OutputRetryFallback fallback = new OutputRetryFallback(validator, 0);

        Optional<String> result = fallback.process("{bad", schema, ReAsk.none());

        assertThat(result).isEmpty();
    }
}
