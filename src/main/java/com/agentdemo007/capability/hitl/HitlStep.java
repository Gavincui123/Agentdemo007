package com.agentdemo007.capability.hitl;

import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.capability.plan.RoutePlanCandidate;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.session.model.StandardQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * HITL 步骤（第四层·{@code @Order(610)}，紧随 {@code RouteDispatchStep(600)}、先于 {@code ToolExecutionStep(650)}）。
 *
 * <p>对标准化 Query 做高风险触发判定（{@link HitlHandler}：转人工意图或高风险关键词）。
 * 触发即建人工工单（{@link HumanTicketService}，PENDING）+ 写 {@code context.hitlTicketId}（§5.14 收口），
 * 再经 {@link HitlDecision} 据超时/权限/带外决议判定。落地收口（{@link StepOutcome}）：
 * <ul>
 *   <li>未触发 → {@code Proceed}（放行后续工具/RAG/上下文/网关）；</li>
 *   <li>人工已确认（Confirmed）→ {@code Proceed}（罕见，带外预批准）；</li>
 *   <li>Pending / Timeout / Denied → {@code ShortCircuit(HITL_TIMEOUT)} 话术 + 审计
 *       （§5.12 HITL 行，零 LLM；不自动执行高风险，不挂起请求——立即返回话术）。</li>
 * </ul>
 * 触发即零 LLM 短路，故先于工具/RAG 执行以避免无谓能力调用。
 * 输入取 {@code standardQuery}（缺失回退 {@code rawInput}，与意图步骤一致）。
 */
@Component
@Order(610)
public class HitlStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(HitlStep.class);

    private final HitlHandler handler;
    private final HitlDecision decision;
    private final HumanTicketService ticketService;
    private final AgentMetrics metrics;

    public HitlStep(HitlHandler handler, HitlDecision decision, HumanTicketService ticketService) {
        this(handler, decision, ticketService, AgentMetrics.NO_OP);
    }

    @Autowired
    public HitlStep(HitlHandler handler, HitlDecision decision, HumanTicketService ticketService,
                    AgentMetrics metrics) {
        this.handler = handler;
        this.decision = decision;
        this.ticketService = ticketService;
        this.metrics = metrics;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        String query = resolveQuery(context);
        Intent intent = context.intent();
        // #135 渐进消费·source 门控（[[routeplan-design]]）：routePlan 为真实 LLM 候选时按 fallbackPolicy
        // 决策（TRANSFER_TO_HUMAN→需人工）；DETERMINISTIC_FALLBACK/null 回退现有 handler.needsReview
        // （noop 测试/兜底候选不采信 routePlan，现有行为不破）。工单/决议仍经 handler.buildRequest +
        // decision.decide（只替换 needsReview 判定源，不替换建单/决议链路）。
        RoutePlan rp = context.routePlan();
        boolean needsReview;
        if (rp != null && rp.source() == RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS) {
            needsReview = (rp.fallbackPolicy() == RoutePlanCandidate.FallbackPolicy.TRANSFER_TO_HUMAN);
        } else {
            needsReview = handler.needsReview(query, intent);
        }
        if (!needsReview) {
            metrics.recordHitl(false);
            return new StepOutcome.Proceed();
        }

        Instant now = Instant.now();
        HitlRequest request = handler.buildRequest(context.sessionId(), query, intent);
        HumanTicket ticket = ticketService.createTicket(request, now);
        context.setHitlTicketId(ticket.id());

        HitlDecision.Outcome outcome = decision.decide(request, ticket, now);

        if (outcome instanceof HitlDecision.Confirmed c) {
            log.info("HITL 人工已确认，放行：sessionId={} ticket={} approver={}", // 审计
                    context.sessionId(), ticket.id(), c.approver());
            metrics.recordHitl(false);
            return new StepOutcome.Proceed();
        }
        if (outcome instanceof HitlDecision.Timeout) {
            ticketService.markTimeout(ticket.id(), now);
            log.warn("HITL 超时未确认，短路话术 + 工单：sessionId={} ticket={}", // 审计
                    context.sessionId(), ticket.id());
            metrics.recordHitl(true);
            return new StepOutcome.ShortCircuit(DegradationScenario.HITL_TIMEOUT);
        }
        if (outcome instanceof HitlDecision.Denied d) {
            log.warn("HITL 权限不足/人工驳回，工单挂起：sessionId={} ticket={} reason={}", // 审计
                    context.sessionId(), ticket.id(), d.reason());
            metrics.recordHitl(true);
            return new StepOutcome.ShortCircuit(DegradationScenario.HITL_TIMEOUT);
        }
        // Pending：带内同步未决议，挂起工单 + 话术短路（不自动执行高风险）
        log.warn("HITL 挂起工单待人工处理，短路话术：sessionId={} ticket={}", // 审计
                context.sessionId(), ticket.id());
        metrics.recordHitl(true);
        return new StepOutcome.ShortCircuit(DegradationScenario.HITL_TIMEOUT);
    }

    private String resolveQuery(PipelineContext context) {
        StandardQuery sq = context.standardQuery();
        return (sq != null) ? sq.text() : context.rawInput();
    }
}
