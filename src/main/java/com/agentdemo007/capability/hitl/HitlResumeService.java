package com.agentdemo007.capability.hitl;

import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineOrchestrator;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.persistence.mq.ChatTurnFinalizer;
import com.agentdemo007.session.model.StandardQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * HITL 恢复执行服务（L2 挂起-恢复·2026-09-18）。
 *
 * <p>审批通过（{@code HumanTicketService#resolve} 首次 TRANSITIONED）或显式恢复接口
 * （{@code POST /admin/hitl/tickets/{id}/resume}，业务校验修复后重驱/幂等重放）后触发异步恢复：
 * <ol>
 *   <li><b>漂移校验（依托业务幂等键为锚，用户裁决）</b>：
 *       ① checkpoint 存在且 ACTIVE（无快照不伪造恢复——重启丢失/非挂起单如实拒绝）；
 *       ② <b>严格锚点匹配</b>：checkpoint 幂等键 == 工单幂等键才可用（{@link
 *       HitlCheckpointService#findActiveForTicket} 键不一致即作废不产出——允许多工单并存，
 *       每单只消费自身键的检查点）；③ 业务键在工单服务中仍指向本工单
 *       （键被 TIMEOUT 后重建的新单抢占 = 已有更新请求，本单陈旧 → 检查点作废 EXPIRED + 拒绝）；</li>
 *   <li><b>业务前置校验（用户裁决：工单状态收尾不自证，结合业务）</b>：
 *       {@link HitlBusinessGate} 对账订单真实状态（退款查支付状态/退货查物流状态），
 *       业务不满足 → 拒绝恢复且<b>不消费检查点</b>（数据修复后可经显式接口重驱）；</li>
 *   <li><b>防重复提交</b>：{@link HitlCheckpointService#consume} CAS（ACTIVE→CONSUMED），
 *       双击审批/并发 resume 仅首个执行，其余幂等拒绝；</li>
 *   <li><b>恢复执行</b>：重建最小 {@link PipelineContext}（身份/输入/意图/routePlan 反序列化）
 *       → 只跑 {@code @Order > 610} 的后段步骤（工具/RAG/工作流/上下文/出答，编排语义与
 *       {@link PipelineOrchestrator} 同源）→ {@link ChatTurnFinalizer} 落会话历史/持久化/审计
 *       （客户下次打开会话即见恢复后的回复）；</li>
 *   <li>终态留痕：恢复回复回写检查点（resumed_at/resume_reply），审计可溯。</li>
 * </ol>
 * 恢复执行生成全新 LLM/工具调用（真实成本），单守护线程 {@code hitl-resume-runner} 串行执行。
 */
public class HitlResumeService {

    private static final Logger log = LoggerFactory.getLogger(HitlResumeService.class);

    private final HumanTicketService ticketService;
    private final HitlCheckpointService checkpointService;
    private final ChatTurnFinalizer finalizer; // 可空（部分单测）：null=跳过落库收口
    private final DegradationPhraseCenter phraseCenter;
    private final AgentMetrics metrics;
    private final List<PipelineStep> postHitlSteps;
    private final ObjectMapper objectMapper;
    private final HitlBusinessGate businessGate; // 恒非空（null → permissive 兜底）
    private final Executor runner;

    public HitlResumeService(HumanTicketService ticketService, HitlCheckpointService checkpointService,
                             ChatTurnFinalizer finalizer, DegradationPhraseCenter phraseCenter,
                             AgentMetrics metrics, List<PipelineStep> steps, ObjectMapper objectMapper) {
        this(ticketService, checkpointService, finalizer, phraseCenter, metrics, steps, objectMapper,
                null, defaultRunner());
    }

    public HitlResumeService(HumanTicketService ticketService, HitlCheckpointService checkpointService,
                             ChatTurnFinalizer finalizer, DegradationPhraseCenter phraseCenter,
                             AgentMetrics metrics, List<PipelineStep> steps, ObjectMapper objectMapper,
                             Executor runner) {
        this(ticketService, checkpointService, finalizer, phraseCenter, metrics, steps, objectMapper,
                null, runner);
    }

    /** 全参构造（业务前置校验门 + 执行器可控）；{@code gate} 为 null 时用宽松门（兼容口径）。 */
    public HitlResumeService(HumanTicketService ticketService, HitlCheckpointService checkpointService,
                             ChatTurnFinalizer finalizer, DegradationPhraseCenter phraseCenter,
                             AgentMetrics metrics, List<PipelineStep> steps, ObjectMapper objectMapper,
                             HitlBusinessGate businessGate, Executor runner) {
        this.ticketService = ticketService;
        this.checkpointService = checkpointService;
        this.finalizer = finalizer;
        this.phraseCenter = phraseCenter;
        this.metrics = (metrics != null) ? metrics : AgentMetrics.NO_OP;
        this.postHitlSteps = postHitlSteps(steps);
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
        this.businessGate = (businessGate != null) ? businessGate : HitlBusinessGate.permissive();
        this.runner = (runner != null) ? runner : defaultRunner();
    }

    private static Executor defaultRunner() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hitl-resume-runner");
            t.setDaemon(true);
            return t;
        });
    }

    /** 恢复结果（诊断/测试用）：executed=是否真实执行了后段流水线；reason=执行/拒绝原因。 */
    public record ResumeResult(boolean executed, String reason) {}

    /** 异步恢复（审批端点触发即返回；单线程串行，避免并发恢复同一工单）。 */
    public void resumeAsync(String ticketId) {
        runner.execute(() -> resume(ticketId));
    }

    /** 同步恢复（测试/诊断入口）：漂移校验 → CAS 消费 → 恢复执行 → 终态留痕。 */
    public ResumeResult resume(String ticketId) {
        Optional<HumanTicket> ticketOpt = ticketService.findById(ticketId);
        if (ticketOpt.isEmpty() || ticketOpt.get().status() != HumanTicket.Status.APPROVED) {
            return new ResumeResult(false, "工单不存在或未处于 APPROVED（仅在首次批准转移后恢复）");
        }
        HumanTicket ticket = ticketOpt.get();

        // ---- 漂移校验（依托业务幂等键）----
        // ①+② 严格锚点匹配：键一致才产出检查点（键不一致已在服务内作废留痕，返回 empty）
        Optional<HitlCheckpointSnapshot> cpOpt =
                checkpointService.findActiveForTicket(ticketId, ticket.idempotencyKey());
        if (cpOpt.isEmpty()) {
            return new ResumeResult(false, "无可用 ACTIVE 检查点（不存在/已作废/检查点幂等键与工单不一致），不伪造恢复");
        }
        HitlCheckpointSnapshot snapshot = cpOpt.get();
        String key = ticket.idempotencyKey();
        // ③ 键活性：业务键仍指向本工单（被 TIMEOUT 重建的新单抢占 = 本单陈旧）
        Optional<HumanTicket> byKey = ticketService.findByIdempotencyKey(key);
        if (byKey.isEmpty() || !byKey.get().id().equals(ticketId)) {
            checkpointService.expire(ticketId, "漂移：幂等键已指向更新的工单（挂起期间有重建请求）");
            return new ResumeResult(false, "漂移：幂等键已指向更新工单，本检查点作废");
        }

        // ---- 业务前置校验（用户裁决：收尾不自证，结合业务；不通过则不消费检查点，可修复后重驱）----
        HitlBusinessGate.Decision biz = businessGate.check(ticket);
        if (!biz.allowed()) {
            log.warn("HITL 恢复被业务校验拒绝：ticket={} key={} reason={}", ticketId, key, biz.reason()); // 审计
            return new ResumeResult(false, "业务前置校验未通过：" + biz.reason());
        }

        // ---- 防重复提交：CAS 消费，仅首个调用者执行 ----
        if (!checkpointService.consume(ticketId)) {
            log.info("HITL 重复恢复提交被拒（检查点已消费）：ticket={} key={}", ticketId, key); // 审计
            return new ResumeResult(false, "重复恢复提交被拒（检查点已消费）");
        }

        try {
            PipelineContext context = rebuildContext(snapshot);
            PipelineResult result = new PipelineOrchestrator(postHitlSteps, phraseCenter, metrics).run(context);
            if (finalizer != null) {
                finalizer.finalizeTurn(context, result); // 会话历史 + 持久化 + 审计刷出（与正常轮次同收口）
            }
            checkpointService.markResumed(ticketId, result.reply(), Instant.now());
            log.info("HITL 恢复执行完成：ticket={} key={} scenario={} replyLen={}", // 审计
                    ticketId, key, result.scenario(), (result.reply() == null ? 0 : result.reply().length()));
            return new ResumeResult(true, result.reply());
        } catch (Exception e) {
            log.error("HITL 恢复执行异常（检查点已消费不回滚，终态留痕）：ticket={} key={}", ticketId, key, e);
            checkpointService.markResumed(ticketId, null, Instant.now());
            return new ResumeResult(false, "恢复执行异常：" + e.getMessage());
        }
    }

    /** 从快照重建最小恢复上下文（routePlan 反序列化失败 → null 规划兜底，门控各步自防御）。 */
    private PipelineContext rebuildContext(HitlCheckpointSnapshot snap) {
        PipelineContext context = new PipelineContext(snap.sessionId(), snap.rawInput());
        if (snap.userId() != null && !snap.userId().isBlank()) {
            context.setUserId(snap.userId());
        }
        if (snap.standardQueryText() != null && !snap.standardQueryText().isBlank()) {
            context.setStandardQuery(StandardQuery.of(snap.standardQueryText()));
        }
        if (snap.intentName() != null && !snap.intentName().isBlank()) {
            try {
                context.setIntent(Intent.valueOf(snap.intentName()));
            } catch (IllegalArgumentException e) {
                log.warn("快照意图名非法，恢复上下文不带意图（下游自防御）：{}", snap.intentName());
            }
        }
        if (snap.routePlanJson() != null && !snap.routePlanJson().isBlank()) {
            try {
                context.setRoutePlan(objectMapper.readValue(snap.routePlanJson(), RoutePlan.class));
            } catch (Exception e) {
                log.warn("快照 routePlan 反序列化失败，按 null 规划兜底：{}", e.getMessage());
            }
        }
        context.setHitlTicketId(snap.ticketId());
        return context;
    }

    /** 后段步骤：{@code @Order > HitlStep.ORDER(610)}，按序（镜像编排器顺序语义）。 */
    private static List<PipelineStep> postHitlSteps(List<PipelineStep> steps) {
        return (steps == null ? List.<PipelineStep>of() : steps).stream()
                .filter(s -> orderOf(s) > HitlStep.ORDER)
                .sorted(Comparator.comparingInt(HitlResumeService::orderOf))
                .toList();
    }

    private static int orderOf(PipelineStep step) {
        Order o = AnnotationUtils.findAnnotation(step.getClass(), Order.class);
        return (o != null) ? o.value() : Ordered.LOWEST_PRECEDENCE;
    }
}
