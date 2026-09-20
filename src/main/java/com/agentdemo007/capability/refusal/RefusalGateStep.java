package com.agentdemo007.capability.refusal;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.observability.audit.AuditEvent;
import com.agentdemo007.observability.audit.AuditEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 拒答闸门（第四层·{@code @Order(690)}：RagStep(660)/WorkflowExecutionStep(670) 之后、
 * ContextBuilder(700) 之前——证据全集到齐后再裁决）。
 *
 * <p>[[refusal-design]] 落地核心：把"无可靠依据时明确拒答"收口为确定性步骤，而非散落各分支。
 * 触发前提（全部满足）：
 * <ol>
 *   <li>{@code app.refusal.mode != off}；</li>
 *   <li>{@code context.groundingMiss == true}——RagStep 漏斗实际执行且终态零片段
 *       （闲聊/路由计划免 RAG 的正常跳过不置位，不会误伤）；</li>
 *   <li><b>全部证据通道为空</b>（ragFragments / runtimeFacts / toolResults）——工具查到了数据
 *       或政策工具命中了条目时模型有据可依，不拒答（混合问题按"有据部分回答+无据部分说明"处理，
 *       交 prompt 约束）；</li>
 *   <li>无其他确定性收口（{@code presetReply}/{@code concurrentReply} 为空——业务驳回/并发合并
 *       优先，本步不抢）；</li>
 *   <li>意图不在免拒答集合：{@code REASONING}（数学/推理无需知识库）、{@code LONG_CONTEXT}
 *       （全文理解）、{@code STRUCTURED_EXTRACTION}（从给定文本抽取）、{@code CHIT_CHAT}、
 *       {@code TRANSFER_TO_HUMAN}、{@code INJECTION}。</li>
 * </ol>
 * 裁决：
 * <ul>
 *   <li><b>prompt 模式</b>（默认）：Proceed——拒答约束已由 {@code SystemAnchorLayer} 依
 *       {@code groundingMiss} 注入 Runtime 块（"严禁自身知识补答"），由终答 LLM 执行；</li>
 *   <li><b>strict 模式</b>：写 {@code context.presetReply} 拒答话术 + REFUSAL 审计事件 + Proceed——
 *       复用 OutputStep 的 presetReply 守卫零 LLM 话术短路；拒答是业务级正确行为，
 *       <b>不标记 degraded</b>（与降级语义正交）。</li>
 * </ul>
 */
@Component
@Order(690)
@EnableConfigurationProperties(RefusalProperties.class)
public class RefusalGateStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(RefusalGateStep.class);

    /** 免拒答意图集合：这些意图本就不依赖知识库证据，grounding 未命中不构成拒答理由。 */
    private static final java.util.Set<Intent> EXEMPT_INTENTS = java.util.Set.of(
            Intent.CHIT_CHAT, Intent.REASONING, Intent.LONG_CONTEXT,
            Intent.STRUCTURED_EXTRACTION, Intent.TRANSFER_TO_HUMAN, Intent.INJECTION);

    private final RefusalProperties props;

    public RefusalGateStep(RefusalProperties props) {
        this.props = props;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        if (props.getMode() == RefusalProperties.Mode.OFF
                || !context.groundingMiss()
                || hasAnyEvidence(context)
                || context.presetReply() != null
                || context.concurrentReply() != null
                || (context.intent() != null && EXEMPT_INTENTS.contains(context.intent()))) {
            return new StepOutcome.Proceed();
        }
        if (props.getMode() == RefusalProperties.Mode.STRICT) {
            context.setPresetReply(props.getPhrase());
            context.addAuditEvent(AuditEvent.of(AuditEventType.REFUSAL, context.traceId(),
                    context.sessionId(), "无依据拒答（strict）：RAG grounding 未命中且证据通道全空"));
            log.info("拒答短路（strict·零 LLM）：sessionId={} intent={} phrase={}",
                    context.sessionId(), context.intent(), abbreviate(props.getPhrase()));
        }
        // prompt 模式：SystemAnchorLayer 依 groundingMiss 注入 Runtime 拒答约束，此处无需动作
        return new StepOutcome.Proceed();
    }

    /** 证据通道判定：任一通道有据（RAG 片段/工具高置信事实/计算结果）即不构成"无依据"。 */
    private static boolean hasAnyEvidence(PipelineContext context) {
        return !context.ragFragments().isEmpty()
                || !context.runtimeFacts().isEmpty()
                || !context.toolResults().isEmpty();
    }

    /** 话术截断（日志口径防刷屏，48 字）。 */
    private static String abbreviate(String s) {
        return (s == null || s.length() <= 48) ? s : s.substring(0, 48) + "…";
    }
}
