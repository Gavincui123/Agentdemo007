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
import java.util.Optional;

/**
 * HITL 步骤（第四层·{@code @Order(610)}，紧随 {@code RouteDispatchStep(600)}、先于 {@code ToolExecutionStep(650)}）。
 *
 * <p>对标准化 Query 做高风险触发判定（{@link HitlHandler}：转人工意图或高风险关键词）。
 * 触发即按<b>业务幂等键</b>（{@link HitlIdempotencyKeyResolver}：订单号+动作，跨会话稳定，
 * 2026-09-18 L2 用户裁决）建人工工单（{@link HumanTicketService}，PENDING）+ 写
 * {@code context.hitlTicketId}（§5.14 收口），再经 {@link HitlDecision} 据超时/权限/带外决议判定。
 * 落地收口（{@link StepOutcome}）：
 * <ul>
 *   <li>未触发 → {@code Proceed}（放行后续工具/RAG/上下文/网关）；</li>
 *   <li>同业务键已有 APPROVED 工单 → {@code Proceed}（幂等放行：人工已批准过该业务动作，
 *       重开会话/重发同单申请不再重复审批——恢复锚定的放行语义）；</li>
 *   <li>人工已确认（Confirmed，带外预批准）→ {@code Proceed}（罕见）；</li>
 *   <li>同业务键已有 PENDING 工单 → 复用该单 + {@code ShortCircuit(HITL_TIMEOUT)}（建单幂等，
 *       重复请求不爆单）；同业务键已有 REJECTED 工单 → {@code ShortCircuit}（人工已驳回，不再重审）；</li>
 *   <li>Pending / Timeout / Denied → {@code ShortCircuit(HITL_TIMEOUT)} 话术 + 审计
 *       （§5.12 HITL 行，零 LLM；不自动执行高风险，不挂起请求——立即返回话术）。</li>
 * </ul>
 * <b>L2 挂起即 checkpoint</b>：Pending 挂起时 {@link HitlCheckpointService#saveFor} 异步落库
 * 最小快照（hitl_checkpoint 表，StateGraph 断点保存语义），供审批通过后恢复执行 610 之后步骤。
 * 触发即零 LLM 短路，故先于工具/RAG 执行以避免无谓能力调用。
 * 输入取 {@code standardQuery}（缺失回退 {@code rawInput}）。
 */
@Component
@Order(610)
public class HitlStep implements PipelineStep {

    /** 步骤序（{@code @Order} 同源常量）：恢复执行据此只跑 610 之后的步骤。 */
    public static final int ORDER = 610;

    private static final Logger log = LoggerFactory.getLogger(HitlStep.class);

    private final HitlHandler handler;
    private final HitlDecision decision;
    private final HumanTicketService ticketService;
    private final AgentMetrics metrics;
    private final HitlIdempotencyKeyResolver idempotencyKeyResolver;
    private final HitlCheckpointService checkpointService; // 可空（兼容构造/部分单测）：null=不做挂起快照

    public HitlStep(HitlHandler handler, HitlDecision decision, HumanTicketService ticketService) {
        this(handler, decision, ticketService, AgentMetrics.NO_OP);
    }

    @Autowired
    public HitlStep(HitlHandler handler, HitlDecision decision, HumanTicketService ticketService,
                    AgentMetrics metrics, HitlIdempotencyKeyResolver idempotencyKeyResolver,
                    HitlCheckpointService checkpointService) {
        this.handler = handler;
        this.decision = decision;
        this.ticketService = ticketService;
        this.metrics = metrics;
        this.idempotencyKeyResolver = idempotencyKeyResolver;
        this.checkpointService = checkpointService;
    }

    public HitlStep(HitlHandler handler, HitlDecision decision, HumanTicketService ticketService,
                    AgentMetrics metrics) {
        // 兼容构造（既有测试/最小装配）：解析器本地构造，无挂起快照
        this(handler, decision, ticketService, metrics, new HitlIdempotencyKeyResolver(), null);
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
        String idempotencyKey = idempotencyKeyResolver.resolve(context); // 业务锚点（订单号+动作），恒非空

        // ---- 建单幂等（2026-09-18 L2）：同业务键（同订单同动作）已有工单 → 不重复建单/不重复审批 ----
        Optional<HumanTicket> existing = ticketService.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            HumanTicket prior = existing.get();
            switch (prior.status()) {
                case PENDING -> {
                    // 复用挂起单（重复请求/网关重试/重开会话不再爆单）；checkpoint 缺失则补挂（重启丢失兜底）
                    context.setHitlTicketId(prior.id());
                    ensureCheckpoint(context, prior, idempotencyKey);
                    log.info("HITL 建单幂等复用 PENDING 工单：sessionId={} key={} ticket={}", // 审计
                            context.sessionId(), idempotencyKey, prior.id());
                    metrics.recordHitl(true);
                    return new StepOutcome.ShortCircuit(DegradationScenario.HITL_TIMEOUT);
                }
                case APPROVED -> {
                    // 幂等放行：人工已批准过该业务动作（恢复锚定的放行语义），带外预批准同语义
                    context.setHitlTicketId(prior.id());
                    log.info("HITL 幂等键命中已批准工单，放行：sessionId={} key={} ticket={}", // 审计
                            context.sessionId(), idempotencyKey, prior.id());
                    metrics.recordHitl(false);
                    return new StepOutcome.Proceed();
                }
                case REJECTED -> {
                    // 人工已驳回该业务动作：不再重审（防"驳回后换会话重提绕过审批"）
                    log.warn("HITL 幂等键命中已驳回工单，拒绝重审：sessionId={} key={} ticket={}", // 审计
                            context.sessionId(), idempotencyKey, prior.id());
                    metrics.recordHitl(true);
                    return new StepOutcome.ShortCircuit(DegradationScenario.HITL_TIMEOUT);
                }
                case TIMEOUT -> {
                    // 超时单：人工未决议，允许重新建单（键索引指向新单，走下方正常流程）
                    log.info("HITL 幂等键命中超时工单，重建：sessionId={} key={} prior={}", // 审计
                            context.sessionId(), idempotencyKey, prior.id());
                }
            }
        }

        HumanTicket ticket = ticketService.createTicket(request, now, idempotencyKey);
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
        log.warn("HITL 挂起工单待人工处理，短路话术：sessionId={} ticket={} key={}", // 审计
                context.sessionId(), ticket.id(), idempotencyKey);
        metrics.recordHitl(true);
        ensureCheckpoint(context, ticket, idempotencyKey); // 挂起即 checkpoint（异步落库，StateGraph 断点语义）
        return new StepOutcome.ShortCircuit(DegradationScenario.HITL_TIMEOUT);
    }

    /**
     * 挂起快照（幂等补挂，<b>严格锚点匹配</b>）：checkpointService 缺省（兼容构造）跳过；
     * 已有 ACTIVE 且<b>幂等键与工单一致</b>的快照不重写；键不一致（漂移/多工单错配）→ 服务内
     * 作废后重建覆盖（upsert 重置 ACTIVE，自愈）。
     */
    private void ensureCheckpoint(PipelineContext context, HumanTicket ticket, String idempotencyKey) {
        if (checkpointService == null) {
            return;
        }
        if (checkpointService.findActiveForTicket(ticket.id(), idempotencyKey).isPresent()) {
            return;
        }
        checkpointService.saveFor(context, ticket, idempotencyKey);
    }

    private String resolveQuery(PipelineContext context) {
        StandardQuery sq = context.standardQuery();
        return (sq != null) ? sq.text() : context.rawInput();
    }
}
