package com.agentdemo007.common.pipeline;

import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.progress.ProgressEmitter;
import com.agentdemo007.common.progress.ProgressEvent;
import com.agentdemo007.observability.AgentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 流水线编排器（收口三件套之三）。
 *
 * <p>按序驱动所有 {@link PipelineStep}（Spring 按 {@code @Order} 自动收集注入），
 * 统一处置四种 {@link StepOutcome}：
 * <ul>
 *   <li>{@link StepOutcome.Proceed} — 推进下一步。</li>
 *   <li>{@link StepOutcome.ShortCircuit} — 立即收口：取话术 + 审计 + 跳过后续（零 LLM，§5.11）。</li>
 *   <li>{@link StepOutcome.Degrade} — 标记降级但继续推进（§5.12）。</li>
 *   <li>{@link StepOutcome.Retry} — 线性模式等价 Proceed（线性不图循环，重试在各 step 内部自理；
 *       图编排模式下由 {@code GraphExecutor} 自循环 + {@code maxIterations} 护栏，§5.13）。</li>
 * </ul>
 * 任何步骤抛异常 → 收口到 {@code INTERNAL} 话术（全局兜底，不抛 5xx，§5.12）。
 *
 * <p>三原则在此交汇：①话术短路=ShortCircuit；②每步降级=Degrade；④统一收口=同一 StepOutcome 出口 + 终端 PipelineResult。
 *
 * <p>Phase 14 编排模式收口：{@code @ConditionalOnProperty(app.pipeline.mode=linear)}，
 * 缺省（{@code matchIfMissing=true}）装配本线性实现；{@code mode=graph} 时让位给
 * {@code GraphExecutor}（由 {@code LangGraphConfig} 条件装配）。{@link PipelineExecutor}
 * 接口是切换锚点——{@link com.agentdemo007.web.ChatController} 只依赖接口，换引擎不换出口。
 *
 * <p>Phase 15 可观测收口：每条返回路径经 {@link AgentMetrics} 记录终端 outcome（ok/degraded/short_circuit，
 * 双标签 scenario）+ run() 起止包夹 duration Timer；Degrade 分支记 per-step 降级计数。指标只经此门面，
 * 禁止散落 {@code MeterRegistry}（同 {@link StepOutcomeAuditor} 之于审计）。{@link AgentMetrics#NO_OP}
 * 为非 Spring 单测便利构造的空实现。
 */
@Component
@ConditionalOnProperty(name = "app.pipeline.mode", havingValue = "linear", matchIfMissing = true)
public class PipelineOrchestrator implements PipelineExecutor {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final List<PipelineStep> steps;
    private final DegradationPhraseCenter phraseCenter;
    private final AgentMetrics metrics;

    /** 非 Spring 单测 / 无指标后端便利构造（指标写入空实现，永不外泄）。 */
    public PipelineOrchestrator(List<PipelineStep> steps, DegradationPhraseCenter phraseCenter) {
        this(steps, phraseCenter, AgentMetrics.NO_OP);
    }

    @Autowired
    public PipelineOrchestrator(List<PipelineStep> steps, DegradationPhraseCenter phraseCenter, AgentMetrics metrics) {
        this.steps = steps;
        this.phraseCenter = phraseCenter;
        this.metrics = metrics;
    }

    public PipelineResult run(PipelineContext context) {
        long start = System.nanoTime();
        ProgressEmitter emitter = context.emitter(); // #136：NO_OP 默认；chatStream 注入 Sse 桥接
        try {
            for (PipelineStep step : steps) {
                if (emitter.closed()) {
                    // 协作式取消：SSE 已超时/断开（用户"停止对话"）→ 后续步骤不再执行（省 LLM 调用与配额）。
                    // 进行中的单步自然结束；本结果仅入审计/持久化，前端已不可达、不再投递。
                    log.info("检测到进度信道已关闭，取消后续步骤（步骤前断点）：next={}", step.name());
                    metrics.recordOutcome(AgentMetrics.Outcome.SHORT_CIRCUIT, DegradationScenario.USER_CANCELLED);
                    return PipelineResult.shortCircuit(phraseCenter.phrase(DegradationScenario.USER_CANCELLED),
                            DegradationScenario.USER_CANCELLED);
                }
                emitter.emit(new ProgressEvent.StepStarted(step.name()));
                StepOutcome outcome;
                try {
                    outcome = step.process(context);
                } catch (Exception e) {
                    log.error("步骤 {} 执行异常，收口到 INTERNAL 话术：{}", step.name(), e.getMessage(), e);
                    emitter.emit(new ProgressEvent.StepFinished(step.name(),
                            ProgressEvent.Outcome.EXCEPTION, DegradationScenario.INTERNAL));
                    StepOutcomeAuditor.auditException(context, step.name(), e.getMessage());
                    metrics.recordOutcome(AgentMetrics.Outcome.SHORT_CIRCUIT, DegradationScenario.INTERNAL);
                    return PipelineResult.shortCircuit(phraseCenter.phrase(DegradationScenario.INTERNAL),
                            DegradationScenario.INTERNAL);
                }
                if (outcome instanceof StepOutcome.ShortCircuit sc) {
                    log.warn("步骤 {} 触发短路：{}（零 LLM，跳过后续）", step.name(), sc.scenario());
                    emitter.emit(new ProgressEvent.StepFinished(step.name(),
                            ProgressEvent.Outcome.SHORT_CIRCUIT, sc.scenario()));
                    StepOutcomeAuditor.audit(context, step.name(), outcome);
                    metrics.recordOutcome(AgentMetrics.Outcome.SHORT_CIRCUIT, sc.scenario());
                    return PipelineResult.shortCircuit(phraseCenter.phrase(sc.scenario()), sc.scenario());
                }
                if (outcome instanceof StepOutcome.Degrade d) {
                    log.warn("步骤 {} 触发降级：{}（继续推进）", step.name(), d.scenario());
                    emitter.emit(new ProgressEvent.StepFinished(step.name(),
                            ProgressEvent.Outcome.DEGRADE, d.scenario()));
                    StepOutcomeAuditor.audit(context, step.name(), outcome);
                    metrics.recordDegradation(d.scenario());
                    continue;
                }
                // Proceed / Retry：推进下一步（线性模式不图循环，Retry 在此等价 Proceed，§5.13）
                // —— 重试语义由各 step 内部自理（如 ToolExecutor 的自纠正循环）
                emitter.emit(new ProgressEvent.StepFinished(step.name(),
                        ProgressEvent.Outcome.PROCEED, null));
            }
            return terminal(context);
        } finally {
            metrics.recordPipelineDuration(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private PipelineResult terminal(PipelineContext context) {
        String reply = context.finalReply();
        List<String> citations = context.ragCitations(); // Phase 20 citation：来源随回复外泄（可追溯 ≠ 一定正确）
        if (context.degraded()) {
            DegradationScenario s = (context.scenario() != null) ? context.scenario() : DegradationScenario.INTERNAL;
            String resolved = (reply != null && !reply.isBlank()) ? reply : phraseCenter.phrase(s);
            metrics.recordOutcome(AgentMetrics.Outcome.DEGRADED, s);
            return PipelineResult.degraded(resolved, s, citations);
        }
        metrics.recordOutcome(AgentMetrics.Outcome.OK, null);
        return PipelineResult.ok(reply, citations);
    }
}
