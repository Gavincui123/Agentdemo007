package com.agentdemo007.persistence.mq;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.session.cache.SessionCacheService;
import com.agentdemo007.session.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话终端后置钩子（Phase 13·异步持久化收口）。
 *
 * <p>流水线结束后由 {@code ChatController} 调用：把 {@link PipelineContext}+{@link PipelineResult}
 * 收口为 {@link ChatTurnEvent} 经 {@link HistoryPersistProducer} 投递会话持久化，并把
 * {@code context.auditEvents()} 经 {@link AuditProducer#publishEach} 批量刷出审计事件。
 *
 * <p>两路独立 try-catch（§5.11 审计不丢）：会话持久化失败不跳过审计刷出，反之亦然；
 * 任一失败仅告警、不向主接口传播异常（§5.12 停 MQ→主接口 200）。投递本身经 {@link MessagePublisher}
 * seam——dev {@code NoopMessagePublisher} 不连 broker；prod {@code RabbitMqMessagePublisher} 吞 AmqpException；
 * 本类的 try-catch 是第二道兜底（应对组装/刷出侧意外，如 {@code ChatTurnEvent.from} 抛 NPE）。
 *
 * <p>非 {@link com.agentdemo007.common.pipeline.PipelineStep}：终端后置钩子是流水线之外的副作用收口，
 * 不参与 {@code StepOutcome} 驱动（§5.14 统一收口：消费收口类型，不私造步间结构）。
 */
@Component
public class ChatTurnFinalizer {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnFinalizer.class);

    private final HistoryPersistProducer historyPersistProducer;
    private final AuditProducer auditProducer;
    private final AgentMetrics metrics;
    private final SessionCacheService sessionCache;

    public ChatTurnFinalizer(HistoryPersistProducer historyPersistProducer, AuditProducer auditProducer) {
        this(historyPersistProducer, auditProducer, AgentMetrics.NO_OP);
    }

    public ChatTurnFinalizer(HistoryPersistProducer historyPersistProducer, AuditProducer auditProducer,
                             AgentMetrics metrics) {
        this(historyPersistProducer, auditProducer, metrics, null);
    }

    @Autowired
    public ChatTurnFinalizer(HistoryPersistProducer historyPersistProducer, AuditProducer auditProducer,
                             AgentMetrics metrics, SessionCacheService sessionCache) {
        this.historyPersistProducer = historyPersistProducer;
        this.auditProducer = auditProducer;
        this.metrics = metrics;
        this.sessionCache = sessionCache;
    }

    /**
     * 流水线结束后收口会话持久化 + 审计刷出。best-effort：任一路失败仅告警，不传播。
     *
     * @param context 流水线上下文（traceId/sessionId/rawInput/intent + auditEvents 之源）
     * @param result  终端结果（reply/degraded/scenario 之源）
     */
    public void finalizeTurn(PipelineContext context, PipelineResult result) {
        safeAppendHistory(context, result);
        safePersistHistory(context, result);
        safeFlushAudit(context);
    }

    /**
     * 会话历史写入（修复：{@code SessionCacheService.append} 此前零调用——历史只读不写，
     * 每轮都被当"新建会话"，多轮上下文与摘要锚点形同虚设）。追加 [User(rawInput), Ai(finalReply)]；
     * {@code USER_CANCELLED}（用户停止，未产出真实回复）不追加。best-effort：失败仅告警。
     */
    private void safeAppendHistory(PipelineContext context, PipelineResult result) {
        if (sessionCache == null) {
            return;
        }
        if (DegradationScenario.USER_CANCELLED.name().equals(result.scenario())) {
            return;
        }
        try {
            sessionCache.append(context.sessionId(), List.of(
                    new ChatMessage.User(context.rawInput()),
                    new ChatMessage.Ai(result.reply())));
        } catch (Exception e) {
            log.warn("会话历史写入失败（不影响主接口）：sessionId={} reason={}",
                    context.sessionId(), e.getMessage());
        }
    }

    private void safePersistHistory(PipelineContext context, PipelineResult result) {
        try {
            historyPersistProducer.publish(ChatTurnEvent.from(context, result));
            metrics.recordMqPublish("history", true);
        } catch (Exception e) {
            metrics.recordMqPublish("history", false);
            log.warn("会话持久化投递失败（不影响主接口）：traceId={}", context.traceId(), e);
        }
    }

    private void safeFlushAudit(PipelineContext context) {
        try {
            auditProducer.publishEach(context.auditEvents());
            metrics.recordMqPublish("audit", true);
        } catch (Exception e) {
            metrics.recordMqPublish("audit", false);
            log.warn("审计事件刷出失败（不影响主接口）：traceId={}", context.traceId(), e);
        }
    }
}
