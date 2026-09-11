package com.agentdemo007.intent;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.observability.AgentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 意图识别步骤（第三层·{@code @Order(500)}）。
 *
 * <p>对标准化 Query 做多层级意图识别（规则→小模型→兜底），写入 {@code context.intent}+{@code intentConfidence}，
 * 对外仅暴露意图枚举（§5.3.4）。落地收口契约（{@link StepOutcome}）：
 * <ul>
 *   <li>识别成功且置信达标 → Proceed；</li>
 *   <li>注入意图 → {@code ShortCircuit(INJECTION)}（零 LLM，§5.11 注入路径）；</li>
 *   <li>置信不达标/模型不可用 → 兜底 {@link Intent#OTHER} + {@code Degrade(UNKNOWN_INTENT)} 继续推进
 *       （§5.12 意图识别行：兜底 UNKNOWN，无"短路"字样即继续，兜底走 SIMPLE 默认路由）。</li>
 * </ul>
 *
 * <p>Phase 15 可观测：每条返回路径记录意图计数（按识别到的最终意图打标签），经 {@link AgentMetrics} 收口。
 */
@Component
@Order(500)
public class IntentRecognitionStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognitionStep.class);

    private final IntentRecognizer recognizer;
    private final IntentClassifier classifier;
    private final AgentMetrics metrics;

    public IntentRecognitionStep(IntentRecognizer recognizer, IntentClassifier classifier) {
        this(recognizer, classifier, AgentMetrics.NO_OP);
    }

    @Autowired
    public IntentRecognitionStep(IntentRecognizer recognizer, IntentClassifier classifier, AgentMetrics metrics) {
        this.recognizer = recognizer;
        this.classifier = classifier;
        this.metrics = metrics;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        // 前置 KeywordTriageStep(@Order 150) 已分诊 → intent 已设：跳过重识别（零 LLM），仍计数供可观测。
        // 注入已在 triage 短路（不到此）；此处 intent 非 null 必为 triage 高置信定论，无需再校验阈值。
        if (context.intent() != null) {
            metrics.recordIntent(context.intent());
            return new StepOutcome.Proceed();
        }
        String query = context.standardQuery() != null
                ? context.standardQuery().text()
                : context.rawInput();
        IntentCategory category = recognizer.recognize(query, context.history());
        context.setIntent(category.intent());
        context.setIntentConfidence(category.confidence());

        if (category.intent() == Intent.INJECTION) {
            log.warn("识别到注入意图，短路：sessionId={}", context.sessionId()); // 审计
            metrics.recordIntent(context.intent());
            return new StepOutcome.ShortCircuit(DegradationScenario.INJECTION);
        }
        if (!classifier.passes(category)) {
            log.warn("意图识别置信不达标，兜底 UNKNOWN 继续：sessionId={} intent={} conf={}",
                    context.sessionId(), category.intent(), category.confidence());
            context.setIntent(Intent.OTHER);
            context.setIntentConfidence(0.0);
            metrics.recordIntent(context.intent());
            return new StepOutcome.Degrade(DegradationScenario.UNKNOWN_INTENT);
        }
        metrics.recordIntent(context.intent());
        return new StepOutcome.Proceed();
    }
}
