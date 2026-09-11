package com.agentdemo007.output;

import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.degradation.DegradationScenario;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化输出网关测试（第七层·LLM 原始输出→结构化校验入口）。
 *
 * <p>覆盖 §5.7：校验通过→正常输出；校验耗尽→兜底话术 + degraded=true（§5.12 结构化输出行）。
 */
class StructuredOutputGatewayTest {

    private final JsonSchemaValidator validator = new JsonSchemaValidator();
    private final DegradationPhraseCenter phraseCenter = new DegradationPhraseCenter();
    private final StructuredOutputGateway gateway =
            new StructuredOutputGateway(new OutputRetryFallback(validator, 2), phraseCenter);

    @Test
    void validOutput_returnsTextNotDegraded() {
        OutputSchema schema = OutputSchema.json(List.of("status"));
        String raw = """
                {"status":"ok","data":42}""";

        OutputResult result = gateway.process(raw, schema, ReAsk.none());

        assertThat(result.degraded()).isFalse();
        assertThat(result.text()).contains("ok");
    }

    @Test
    void lenientSchema_alwaysValid() {
        OutputResult result = gateway.process("任意自由文本回复", null, ReAsk.none());

        assertThat(result.degraded()).isFalse();
        assertThat(result.text()).isEqualTo("任意自由文本回复");
    }

    @Test
    void schemaExhausted_returnsFallbackPhraseDegraded() {
        OutputSchema schema = OutputSchema.json(List.of("status"));
        String raw = "{bad json";

        OutputResult result = gateway.process(raw, schema, ReAsk.none());

        assertThat(result.degraded()).isTrue();
        assertThat(result.text()).isEqualTo(DegradationScenario.OUTPUT_FALLBACK.phrase());
    }

    @Test
    void schemaExhausted_phraseOverrideApplied() {
        phraseCenter.putOverride(DegradationScenario.OUTPUT_FALLBACK, "自定义兜底话术");
        OutputSchema schema = OutputSchema.json(List.of("status"));

        OutputResult result = gateway.process("{bad", schema, ReAsk.none());

        assertThat(result.degraded()).isTrue();
        assertThat(result.text()).isEqualTo("自定义兜底话术");
    }

    @Test
    void reAskRecovers_returnsCorrectedNotDegraded() {
        OutputSchema schema = OutputSchema.json(List.of("status"));
        ReAsk reAsk = (last, err) -> Optional.of("""
                {"status":"ok"}"""); // 重问即修正

        OutputResult result = gateway.process("{bad", schema, reAsk);

        assertThat(result.degraded()).isFalse();
        assertThat(result.text()).contains("ok");
    }
}
