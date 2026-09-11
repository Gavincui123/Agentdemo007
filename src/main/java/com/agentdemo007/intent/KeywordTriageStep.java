package com.agentdemo007.intent;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.intent.rule.RuleMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 关键词前置分诊步骤（第三层·{@code @Order(150)}，流水线第一步）。
 *
 * <p>在 {@code QueryRewriter(300)}/{@code IntentRecognitionStep(500)} 之前对 {@code rawInput}
 * 跑 {@link RuleMatcher}——这是「第一层关键词识别」的<b>真正前置</b>，避免闲聊被无条件改写
 * 浪费一轮小模型调用（此前关键词层在 500，重写 LLM 在 300 已先跑）：
 * <ul>
 *   <li>命中注入 → {@code ShortCircuit(INJECTION)}（零 LLM，比 IntentRecognitionStep 更早拦截，
 *       连改写都省；记 intent 供审计）；</li>
 *   <li>命中且置信达标 → 设 {@code context.intent}+{@code intentConfidence}，返回 Proceed
 *       （下游 QueryRewriter 见 chit-chat 即跳过改写、IntentRecognitionStep 见 intent 已设即跳过重识别）；</li>
 *   <li>无命中/冲突/置信不达标 → 返回 Proceed（不设 intent），交 QueryRewriter+IntentRecognition 正常处理。</li>
 * </ul>
 *
 * <p>关键词表经 {@code IntentKeywordProperties} 配置化（Nacos 覆盖，见 {@link IntentConfig}）。
 * 分诊读 {@code rawInput}（此时 standardQuery 尚未由改写器产出），故对原始输入直接命中，
 * 闲聊/注入在改写之前即定论。
 */
@Component
@Order(150)
public class KeywordTriageStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(KeywordTriageStep.class);

    private final RuleMatcher ruleMatcher;
    private final IntentClassifier classifier;

    public KeywordTriageStep(RuleMatcher ruleMatcher, IntentClassifier classifier) {
        this.ruleMatcher = ruleMatcher;
        this.classifier = classifier;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        String query = context.rawInput();
        var rule = ruleMatcher.match(query, context.history());
        if (rule.isEmpty()) {
            return new StepOutcome.Proceed(); // 无命中/冲突 → 交后续正常处理
        }
        IntentCategory category = rule.get();
        if (category.intent() == Intent.INJECTION) {
            // 注入零 LLM 短路（记 intent 供审计），比 IntentRecognitionStep 更早
            context.setIntent(Intent.INJECTION);
            context.setIntentConfidence(category.confidence());
            log.warn("前置分诊识别注入，短路（零 LLM）：sessionId={}", context.sessionId());
            return new StepOutcome.ShortCircuit(DegradationScenario.INJECTION);
        }
        if (!classifier.passes(category)) {
            return new StepOutcome.Proceed(); // 置信不达标 → 不定论，交后续小模型识别
        }
        context.setIntent(category.intent());
        context.setIntentConfidence(category.confidence());
        log.debug("前置分诊命中：sessionId={} intent={} conf={}",
                context.sessionId(), category.intent(), category.confidence());
        return new StepOutcome.Proceed();
    }
}
