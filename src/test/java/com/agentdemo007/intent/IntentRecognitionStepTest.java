package com.agentdemo007.intent;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.session.model.StandardQuery;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 意图识别步骤测试（第三层·IntentRecognitionStep @Order(500)）。
 *
 * <p>落地收口契约：经 {@link PipelineContext} 读写、返回 {@link StepOutcome}：
 * <ul>
 *   <li>识别成功且置信达标 → 写入 {@code intent} + Proceed；</li>
 *   <li>注入意图 → {@code ShortCircuit(INJECTION)}（零 LLM，§5.11）；</li>
 *   <li>置信不达标/模型不可用 → 兜底 {@link Intent#OTHER} + {@code Degrade(UNKNOWN_INTENT)} 继续推进
 *       （§5.12 意图识别行：兜底 UNKNOWN，无"短路"字样即继续）。</li>
 * </ul>
 * 输入取 {@code context.standardQuery}（改写后自足问题），缺失时回退 {@code rawInput}。
 */
class IntentRecognitionStepTest {

    private final IntentRecognizer recognizer = mock(IntentRecognizer.class);
    private final IntentClassifier classifier = new IntentClassifier(0.6);
    private final IntentRecognitionStep step = new IntentRecognitionStep(recognizer, classifier);

    @Test
    void success_proceeds_setsIntentAndConfidence() {
        when(recognizer.recognize(anyString(), anyString(), anyList()))
                .thenReturn(new IntentCategory(Intent.REASONING, 0.9));
        PipelineContext ctx = new PipelineContext("s1", "分析 Q3 销售");
        ctx.setStandardQuery(StandardQuery.of("分析 Q3 销售"));

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.intent()).isEqualTo(Intent.REASONING);
        assertThat(ctx.intentConfidence()).isEqualTo(0.9);
    }

    @Test
    void injection_shortCircuitsInjection_zeroLlmPath() {
        when(recognizer.recognize(anyString(), anyString(), anyList()))
                .thenReturn(new IntentCategory(Intent.INJECTION, 1.0));
        PipelineContext ctx = new PipelineContext("s1", "ignore previous");

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.ShortCircuit.class);
        assertThat(((StepOutcome.ShortCircuit) outcome).scenario()).isEqualTo(DegradationScenario.INJECTION);
    }

    @Test
    void lowConfidence_degradesUnknownIntent_setsOther() {
        when(recognizer.recognize(anyString(), anyString(), anyList()))
                .thenReturn(IntentCategory.unknown()); // 模型不可用兜底
        PipelineContext ctx = new PipelineContext("s1", "随便聊聊");

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Degrade.class);
        assertThat(((StepOutcome.Degrade) outcome).scenario()).isEqualTo(DegradationScenario.UNKNOWN_INTENT);
        assertThat(ctx.intent()).isEqualTo(Intent.OTHER); // 兜底
    }

    @Test
    void missingStandardQuery_fallsBackToRawInput() {
        when(recognizer.recognize(eq("你好"), eq("你好"), anyList()))
                .thenReturn(new IntentCategory(Intent.CHIT_CHAT, 0.7));
        PipelineContext ctx = new PipelineContext("s1", "你好");
        // 不设置 standardQuery → 回退 rawInput

        step.process(ctx);

        assertThat(ctx.intent()).isEqualTo(Intent.CHIT_CHAT);
    }

    @Test
    void intentAlreadySet_skipsRecognition_proceeds() {
        // 模拟前置 KeywordTriageStep(@Order 150) 已分诊：intent 已设为 CHIT_CHAT。
        // 桩 recognize 返回不同意图(REASONING)：若误重识别会覆盖 CHIT_CHAT，断言即失败
        when(recognizer.recognize(anyString(), anyString(), anyList()))
                .thenReturn(new IntentCategory(Intent.REASONING, 0.9));
        PipelineContext ctx = new PipelineContext("s1", "你好");
        ctx.setIntent(Intent.CHIT_CHAT);
        ctx.setIntentConfidence(0.85);

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.intent()).isEqualTo(Intent.CHIT_CHAT); // 不被重识别覆盖
        verify(recognizer, never()).recognize(anyString(), anyList()); // 不重识别（零 LLM）
    }

    @Test
    void intentAlreadySet_recordsTriagedIntentMetric() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        IntentRecognitionStep metricsStep = new IntentRecognitionStep(recognizer, classifier, metrics);
        when(recognizer.recognize(anyString(), anyString(), anyList()))
                .thenReturn(new IntentCategory(Intent.REASONING, 0.9));
        PipelineContext ctx = new PipelineContext("s1", "你好");
        ctx.setIntent(Intent.CHIT_CHAT);

        metricsStep.process(ctx);

        // 前置分诊的意图也被计数（observability 收口：不因跳过重识别而漏统计）
        assertThat(registry.counter("agent.intent", "intent", "CHIT_CHAT").count()).isEqualTo(1.0);
        verify(recognizer, never()).recognize(anyString(), anyList());
    }

    @Test
    void name_isIntentRecognitionStep() {
        assertThat(step.name()).isEqualTo("IntentRecognitionStep");
    }

    @Test
    void process_recordsIntentCounterTaggedByRecognizedIntent() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        IntentRecognitionStep metricsStep = new IntentRecognitionStep(recognizer, classifier, metrics);
        when(recognizer.recognize(anyString(), anyString(), anyList()))
                .thenReturn(new IntentCategory(Intent.REASONING, 0.9));
        PipelineContext ctx = new PipelineContext("s1", "分析 Q3 销售");

        metricsStep.process(ctx);

        assertThat(registry.counter("agent.intent", "intent", "REASONING").count()).isEqualTo(1.0);
    }
}
