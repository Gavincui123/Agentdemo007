package com.agentdemo007.capability.hitl;

import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.capability.plan.RoutePlanCandidate;
import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentdemo007.capability.plan.RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * HITL 恢复执行服务测试（2026-09-18 L2·用户裁决）。
 *
 * <p>覆盖：① 恢复执行只跑 610 后段步骤并产出终态回复；② <b>漂移校验（依托业务幂等键）</b>——
 * 键不一致/键被更新工单抢占 → 检查点作废 + 拒绝恢复；③ <b>防重复提交</b>——checkpoint CAS 消费，
 * 双击审批/并发 resume 仅首个执行；④ 非法前置（无检查点/非 APPROVED）不伪造恢复。
 * scripted 后段步骤（@Order 650/800）证编排语义；finalizer 传 null（落库收口在装配层注入）。
 */
class HitlResumeServiceTest {

    /** scripted 后段步骤：计数 + 写 toolResults（证真跑过）。 */
    @Order(650)
    static class ScriptedToolStep implements PipelineStep {
        final AtomicInteger runs = new AtomicInteger();

        @Override
        public StepOutcome process(PipelineContext context) {
            runs.incrementAndGet();
            context.toolResults().add("工具取数完成");
            return new StepOutcome.Proceed();
        }
    }

    /** scripted 出答步骤：写 finalReply（编排器 terminal 取此为回复）。 */
    @Order(800)
    static class ScriptedOutputStep implements PipelineStep {
        final AtomicInteger runs = new AtomicInteger();

        @Override
        public StepOutcome process(PipelineContext context) {
            runs.incrementAndGet();
            context.setFinalReply("已批准：退款已提交，预计 1-3 个工作日到账");
            return new StepOutcome.Proceed();
        }
    }

    /** scripted HITL 前段步骤（@Order 500 < 610）：恢复执行<b>不得</b>重跑（断点语义）。 */
    @Order(500)
    static class ScriptedPreHitlStep implements PipelineStep {
        final AtomicInteger runs = new AtomicInteger();

        @Override
        public StepOutcome process(PipelineContext context) {
            runs.incrementAndGet();
            return new StepOutcome.Proceed();
        }
    }

    private final ScriptedPreHitlStep preHitlStep = new ScriptedPreHitlStep();
    private final ScriptedToolStep toolStep = new ScriptedToolStep();
    private final ScriptedOutputStep outputStep = new ScriptedOutputStep();
    private final HumanTicketService ticketService = new HumanTicketService();
    private final HitlCheckpointService checkpoints =
            new HitlCheckpointService(null, new ObjectMapper(), Runnable::run);
    private final HitlResumeService service = new HitlResumeService(
            ticketService, checkpoints, null, new DegradationPhraseCenter(),
            com.agentdemo007.observability.AgentMetrics.NO_OP,
            List.of(preHitlStep, toolStep, outputStep),
            new ObjectMapper(), Runnable::run);

    private static final String KEY = "hitl:refund:ORD-001";

    /** 挂起场景构造：PENDING 工单（带键）+ ACTIVE 检查点（同键）。 */
    private HumanTicket suspendedTicket(String sessionId) {
        HumanTicket ticket = ticketService.createTicket(
                new HitlRequest(sessionId, "我要退款 ORD-001", "高风险退款，转人工", HitlRequest.RISK_HIGH),
                java.time.Instant.now(), KEY);
        checkpoints.saveAsync(new HitlCheckpointSnapshot(ticket.id(), KEY, "trace-1", sessionId,
                "10086", "我要退款 ORD-001", "退款 ORD-001", "TRANSFER_TO_HUMAN", null, 1L));
        return ticket;
    }

    @Test
    void resume_approved_runsPostHitlStepsOnly_andProducesReply() {
        HumanTicket ticket = suspendedTicket("sess-A");
        assertThat(ticketService.resolve(ticket.id(), HumanTicket.Status.APPROVED, java.time.Instant.now()))
                .isEqualTo(HumanTicketService.ResolveResult.TRANSITIONED);

        HitlResumeService.ResumeResult result = service.resume(ticket.id());

        assertThat(result.executed()).isTrue();
        assertThat(result.reason()).contains("退款已提交"); // 终态回复（出答步产出）
        assertThat(preHitlStep.runs.get()).isZero();        // 610 前段不重跑（断点语义）
        assertThat(toolStep.runs.get()).isEqualTo(1);       // 610 后段真跑
        assertThat(outputStep.runs.get()).isEqualTo(1);
        assertThat(checkpoints.findActive(ticket.id())).isEmpty(); // 检查点已消费（CONSUMED）
    }

    @Test
    void resume_doubleSubmit_secondRejected_noRerun() {
        // 防重复提交：双击审批 → 两次 resume，仅首个执行（顺序路径：检查点已 CONSUMED → 非 ACTIVE 拒绝；
        // 并发竞态路径由 HitlCheckpointServiceTest.consume_casOnlyFirstCallerWins 的 CAS 断言覆盖）
        HumanTicket ticket = suspendedTicket("sess-A");
        ticketService.resolve(ticket.id(), HumanTicket.Status.APPROVED, java.time.Instant.now());

        HitlResumeService.ResumeResult first = service.resume(ticket.id());
        HitlResumeService.ResumeResult second = service.resume(ticket.id());

        assertThat(first.executed()).isTrue();
        assertThat(second.executed()).isFalse();
        assertThat(second.reason()).contains("检查点");
        assertThat(toolStep.runs.get()).isEqualTo(1); // 后段步骤不重跑（LLM/工具成本不翻倍）
    }

    @Test
    void resume_keyMismatchWithCheckpoint_driftExpired_refused() {
        // 漂移校验①：工单键与检查点键不一致 → 作废 + 拒绝（不锚定错误业务实体执行）
        HumanTicket ticket = ticketService.createTicket(
                new HitlRequest("sess-A", "我要退款 ORD-001", "r", HitlRequest.RISK_HIGH),
                java.time.Instant.now(), KEY);
        checkpoints.saveAsync(new HitlCheckpointSnapshot(ticket.id(), "hitl:refund:ORD-999", "trace-1",
                "sess-A", null, "我要退款 ORD-001", null, null, null, 1L));
        ticketService.resolve(ticket.id(), HumanTicket.Status.APPROVED, java.time.Instant.now());

        HitlResumeService.ResumeResult result = service.resume(ticket.id());

        assertThat(result.executed()).isFalse();
        assertThat(result.reason()).contains("幂等键"); // 严格锚点匹配：键不一致不产出检查点
        assertThat(toolStep.runs.get()).isZero();
        assertThat(checkpoints.findActive(ticket.id())).isEmpty(); // 已作废（EXPIRED）
    }

    @Test
    void resume_keyRemappedToNewerTicket_driftExpired_refused() {
        // 漂移校验②：业务键已指向另一工单（挂起期间同键重建/键被抢占）→ 批准旧单属陈旧决议，
        // 恢复会错绑业务实体 → 作废 + 拒绝（防线在状态机守卫之外兜底，工单存储换 DB 实现后同样有效）
        HumanTicket stale = ticketService.createTicket(
                new HitlRequest("sess-A", "我要退款 ORD-001", "r", HitlRequest.RISK_HIGH),
                java.time.Instant.now(), KEY);
        checkpoints.saveAsync(new HitlCheckpointSnapshot(stale.id(), KEY, "trace-1", "sess-A",
                null, "我要退款 ORD-001", null, null, null, 1L));
        HumanTicket newer = ticketService.createTicket( // 同键第二单：键索引 → newer
                new HitlRequest("sess-B", "我要退款 ORD-001", "r", HitlRequest.RISK_HIGH),
                java.time.Instant.now(), KEY);
        ticketService.resolve(stale.id(), HumanTicket.Status.APPROVED, java.time.Instant.now()); // 批准陈旧单

        HitlResumeService.ResumeResult result = service.resume(stale.id());

        assertThat(result.executed()).isFalse();
        assertThat(result.reason()).contains("幂等键已指向更新工单");
        assertThat(toolStep.runs.get()).isZero();
        assertThat(checkpoints.findActive(stale.id())).isEmpty(); // 作废（EXPIRED）
        // 新单仍 PENDING 待审批（批准动作不被吞：走新单的正常审批-恢复链路）
        assertThat(ticketService.findById(newer.id()).orElseThrow().status()).isEqualTo(HumanTicket.Status.PENDING);
    }

    @Test
    void resume_noCheckpoint_refused_noFakeResume() {
        // 无 ACTIVE 检查点（重启丢失/非挂起单）→ 不伪造恢复
        HumanTicket ticket = ticketService.createTicket(
                new HitlRequest("sess-A", "我要退款 ORD-001", "r", HitlRequest.RISK_HIGH),
                java.time.Instant.now(), KEY);
        ticketService.resolve(ticket.id(), HumanTicket.Status.APPROVED, java.time.Instant.now());

        HitlResumeService.ResumeResult result = service.resume(ticket.id());

        assertThat(result.executed()).isFalse();
        assertThat(result.reason()).contains("ACTIVE 检查点");
        assertThat(toolStep.runs.get()).isZero();
    }

    @Test
    void resume_notApproved_refused() {
        HumanTicket ticket = suspendedTicket("sess-A"); // 仍 PENDING

        HitlResumeService.ResumeResult result = service.resume(ticket.id());

        assertThat(result.executed()).isFalse();
        assertThat(result.reason()).contains("APPROVED");
        assertThat(toolStep.runs.get()).isZero();
    }

    @Test
    void resume_rejectedTicket_refused() {
        HumanTicket ticket = suspendedTicket("sess-A");
        ticketService.resolve(ticket.id(), HumanTicket.Status.REJECTED, java.time.Instant.now());

        HitlResumeService.ResumeResult result = service.resume(ticket.id());

        assertThat(result.executed()).isFalse();
        assertThat(toolStep.runs.get()).isZero();
    }

    // ---- 防"恢复上下文丢路由决策"：快照 routePlan JSON 反序列化回上下文 ----

    @Test
    void rebuildContext_routePlanJsonRoundTrip_preservedForGating() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RoutePlanCandidate candidate = new RoutePlanCandidate("refund", false, true,
                List.of("query_order"), List.of("order"), RoutePlanCandidate.RiskLevel.HIGH, true,
                WORKFLOW_FIRST);
        RoutePlan plan = new RoutePlan(candidate, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, List.of());
        String json = mapper.writeValueAsString(plan);
        HumanTicket ticket = ticketService.createTicket(
                new HitlRequest("sess-A", "我要退款 ORD-001", "r", HitlRequest.RISK_HIGH),
                java.time.Instant.now(), KEY);
        checkpoints.saveAsync(new HitlCheckpointSnapshot(ticket.id(), KEY, "trace-1", "sess-A",
                "10086", "我要退款 ORD-001", "退款 ORD-001", "TRANSFER_TO_HUMAN", json, 1L));
        ticketService.resolve(ticket.id(), HumanTicket.Status.APPROVED, java.time.Instant.now());

        HitlResumeService.ResumeResult result = service.resume(ticket.id());

        assertThat(result.executed()).isTrue(); // routePlan 反序列化不阻断恢复（失败才 null 兜底）
        assertThat(outputStep.runs.get()).isEqualTo(1);
    }

    // ---- 2026-09-18 同日扩展（用户裁决）：业务前置校验（工单收尾不自证，结合业务）----

    @Test
    void resume_blockedByBusinessGate_checkpointNotConsumed_canReDriveAfterFix() {
        // 退款工单对账订单支付状态：UNPAID → 拒绝恢复且不消费检查点（数据修复后可重驱）；
        // 订单修复为 PAID → 再次恢复成功（显式恢复接口/重复批准的重驱语义）
        com.agentdemo007.persistence.repository.BizOrderRepository orders =
                org.mockito.Mockito.mock(com.agentdemo007.persistence.repository.BizOrderRepository.class);
        when(orders.findById("ORD-001")).thenReturn(java.util.Optional.of(new com.agentdemo007.persistence.entity.BizOrderEntity(
                "ORD-001", "U1001", "UNPAID", "DELIVERED", "NONE", new java.math.BigDecimal("199"), java.time.Instant.now())));
        HitlResumeService gated = new HitlResumeService(ticketService, checkpoints, null,
                new DegradationPhraseCenter(), com.agentdemo007.observability.AgentMetrics.NO_OP,
                List.of(preHitlStep, toolStep, outputStep), new ObjectMapper(),
                new HitlBusinessGate(orders), Runnable::run);
        HumanTicket ticket = suspendedTicket("sess-A"); // KEY 含 ORD-001 → 业务门对账该订单
        ticketService.resolve(ticket.id(), HumanTicket.Status.APPROVED, java.time.Instant.now());

        HitlResumeService.ResumeResult blocked = gated.resume(ticket.id());

        assertThat(blocked.executed()).isFalse();
        assertThat(blocked.reason()).contains("业务前置校验未通过").contains("未支付");
        assertThat(toolStep.runs.get()).isZero();
        assertThat(checkpoints.findActive(ticket.id())).isPresent(); // 检查点未被消费（可重驱）

        // 数据修复：订单已支付 → 重驱恢复成功
        when(orders.findById("ORD-001")).thenReturn(java.util.Optional.of(new com.agentdemo007.persistence.entity.BizOrderEntity(
                "ORD-001", "U1001", "PAID", "DELIVERED", "NONE", new java.math.BigDecimal("199"), java.time.Instant.now())));
        HitlResumeService.ResumeResult retry = gated.resume(ticket.id());

        assertThat(retry.executed()).isTrue();
        assertThat(toolStep.runs.get()).isEqualTo(1);
        assertThat(checkpoints.findActive(ticket.id())).isEmpty(); // 此时才消费
    }
}
