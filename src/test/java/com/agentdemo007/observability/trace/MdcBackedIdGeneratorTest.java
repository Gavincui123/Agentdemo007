package com.agentdemo007.observability.trace;

import com.agentdemo007.common.trace.TraceId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MdcBackedIdGenerator 单测（Phase 23·traceId 对齐）。
 *
 * <p>验三件事：MDC 有合法 traceId → OTel traceId 沿用之（对齐核心诉求：Jaeger 可凭
 * X-Trace-Id 检索）；MDC 非法/缺失 → 退回随机（宁要新 trace 也不要非法 W3C 格式）；
 * spanId 恒随机 16-hex。
 */
class MdcBackedIdGeneratorTest {

    private final MdcBackedIdGenerator generator = new MdcBackedIdGenerator();

    @AfterEach
    void cleanUp() {
        MDC.remove(TraceId.MDC_KEY);
    }

    @Test
    void generateTraceId_usesMdcWhenValid() {
        String id = UUID.randomUUID().toString().replace("-", "");
        MDC.put(TraceId.MDC_KEY, id);

        assertThat(generator.generateTraceId()).isEqualTo(id);
    }

    @Test
    void generateTraceId_normalizesUppercase() {
        String id = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        MDC.put(TraceId.MDC_KEY, id);

        assertThat(generator.generateTraceId()).isEqualTo(id.toLowerCase());
    }

    @Test
    void generateTraceId_fallsBackToRandomWhenMdcInvalid() {
        MDC.put(TraceId.MDC_KEY, "not-a-valid-trace-id");

        String generated = generator.generateTraceId();

        assertThat(generated).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(generated).isNotEqualTo("not-a-valid-trace-id");
    }

    @Test
    void generateTraceId_fallsBackToRandomWhenMdcAbsent() {
        assertThat(generator.generateTraceId()).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void generateSpanId_isRandom16Hex() {
        assertThat(generator.generateSpanId()).hasSize(16).matches("[0-9a-f]{16}");
    }
}
