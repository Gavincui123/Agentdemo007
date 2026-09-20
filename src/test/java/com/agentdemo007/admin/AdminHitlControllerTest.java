package com.agentdemo007.admin;

import com.agentdemo007.capability.hitl.HitlBusinessGate;
import com.agentdemo007.capability.hitl.HitlRequest;
import com.agentdemo007.capability.hitl.HitlResumeService;
import com.agentdemo007.capability.hitl.HumanTicket;
import com.agentdemo007.capability.hitl.HumanTicketService;
import com.agentdemo007.capability.workflow.AfterSaleBusinessExecutor;
import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理台·HITL 工单端点单测（Phase 19·T97；2026-09-18 L2 挂起-恢复扩展；2026-09-20 提交制）。
 *
 * <p>{@code GET /admin/hitl/tickets} 列<b>全量</b>工单（PENDING 优先，已决议单留痕·提交制 2026-09-20）；
 * {@code POST /admin/hitl/tickets/{id}/confirm}
 * 与 {@code /reject} 流转工单到 APPROVED/REJECTED；{@code /resume} 为显式恢复接口（APPROVED 单重驱）。
 * 未知 id → 404 NOT_FOUND（②降级不 5xx，同形 HTTP 200 + code）。
 * <b>业务前置校验</b>（用户裁决：工单收尾不自证）：confirm 放行前过 {@link HitlBusinessGate}——
 * 业务不满足 → 409 + 工单保持 PENDING（可修复后重审），不触发后续动作。
 * <b>决议=事件（提交制 2026-09-20·用户裁决：审批是事件、Agent 最小权限）</b>：决议只是工单状态变更——
 * 工作流审批单（{@code wfa:} 键）批准触发业务系统执行 mock（{@link AfterSaleBusinessExecutor}），
 * 无请求内唤醒/检查点恢复；HITL 检查点单批准走异步恢复。同决议重复提交幂等成功且不重复触发；
 * 反向决议 → 409 CONFLICT（状态不回翻）。
 * {@code resolvedAt} 取注入式 {@link Clock}（可测，匹配 AlertRuleEvaluator 模式）。
 *
 * <p>直构控制器（同 {@code ChatControllerTest} 约定），断言 {@link UnifiedResponse}。
 */
class AdminHitlControllerTest {

    private final HumanTicketService service = new HumanTicketService();
    private final HitlResumeService resumeService = mock(HitlResumeService.class);
    private final HitlBusinessGate businessGate = mock(HitlBusinessGate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"), ZoneOffset.UTC);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AfterSaleBusinessExecutor> businessExecutorProvider =
            mock(ObjectProvider.class);
    private final AfterSaleBusinessExecutor businessExecutor = mock(AfterSaleBusinessExecutor.class);
    private final AdminHitlController controller =
            new AdminHitlController(service, resumeService, businessGate, clock, businessExecutorProvider);

    private HumanTicket pendingTicket(String sessionId) {
        when(businessGate.check(any())).thenReturn(HitlBusinessGate.Decision.allow("默认放行")); // 默认业务门通过
        when(businessExecutorProvider.getIfAvailable()).thenReturn(businessExecutor);
        return service.createTicket(
                new HitlRequest(sessionId, "转人工", "用户请求", HitlRequest.RISK_HIGH),
                Instant.parse("2026-09-08T09:00:00Z"));
    }

    /** 工作流审批单（wfa: 幂等键，提交制建单产物形态）。 */
    private HumanTicket workflowApprovalTicket(String key) {
        when(businessGate.check(any())).thenReturn(HitlBusinessGate.Decision.allow("订单已支付，满足退款前提"));
        when(businessExecutorProvider.getIfAvailable()).thenReturn(businessExecutor);
        return service.createTicket(
                new HitlRequest("s1", "售后审批｜退款｜订单 ORD-001", "高风险售后工作流人工审批",
                        HitlRequest.RISK_HIGH),
                Instant.parse("2026-09-08T09:00:00Z"), key);
    }

    @Test
    void tickets_returnsAll_resolvedIncluded_pendingFirst() {
        // 提交制留痕（2026-09-20）：决议后工单不得从控制台消失——批准/驳回是事件，工单是唯一审计留痕
        HumanTicket t1 = pendingTicket("s1");
        HumanTicket t2 = pendingTicket("s2");
        service.resolve(t2.id(), HumanTicket.Status.APPROVED, Instant.parse("2026-09-08T09:30:00Z"));

        UnifiedResponse resp = controller.tickets();

        assertThat(resp.code()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        java.util.List<HitlTicketSummary> data = (java.util.List<HitlTicketSummary>) resp.data();
        assertThat(data).hasSize(2); // PENDING 与已决议单都在
        assertThat(data.get(0).id()).isEqualTo(t1.id()); // PENDING 优先
        assertThat(data.get(0).status()).isEqualTo("PENDING");
        assertThat(data.get(1).id()).isEqualTo(t2.id());
        assertThat(data.get(1).status()).isEqualTo("APPROVED"); // 已决议单留痕（前端渲染已确认徽章）
        assertThat(data.get(1).resolvedAt()).isEqualTo(Instant.parse("2026-09-08T09:30:00Z"));
    }

    @Test
    void tickets_afterConfirm_ticketStaysListed_withApprovedBadge() {
        // 用户实测回归钉：确认成功后刷新列表，工单以 APPROVED 留痕而非消失
        HumanTicket t = workflowApprovalTicket("wfa:REFUND:ORD-001");
        controller.confirm(t.id());

        UnifiedResponse resp = controller.tickets();

        @SuppressWarnings("unchecked")
        java.util.List<HitlTicketSummary> data = (java.util.List<HitlTicketSummary>) resp.data();
        assertThat(data).extracting(HitlTicketSummary::id).contains(t.id());
        assertThat(data).filteredOn(s -> s.id().equals(t.id()))
                .allSatisfy(s -> assertThat(s.status()).isEqualTo("APPROVED"));
    }

    @Test
    void confirm_setsApprovedAndTriggersResumeOnce() {
        HumanTicket t = pendingTicket("s1");

        UnifiedResponse resp = controller.confirm(t.id());

        assertThat(resp.code()).isEqualTo(0);
        HitlTicketSummary summary = (HitlTicketSummary) resp.data();
        assertThat(summary.status()).isEqualTo("APPROVED");
        assertThat(summary.resolvedAt()).isEqualTo(Instant.parse("2026-09-08T10:00:00Z"));
        assertThat(service.findById(t.id()).orElseThrow().status()).isEqualTo(HumanTicket.Status.APPROVED);
        verify(resumeService).resumeAsync(t.id()); // 首次转移 → 触发异步恢复
    }

    @Test
    void confirmRepeat_idempotentSuccess_doesNotTriggerResumeAgain() {
        HumanTicket t = pendingTicket("s1");
        controller.confirm(t.id());

        UnifiedResponse resp = controller.confirm(t.id()); // 双击/重放

        assertThat(resp.code()).isEqualTo(0); // 幂等成功
        verify(resumeService, times(1)).resumeAsync(t.id()); // 恢复仅触发一次（重复提交不重触发）
    }

    @Test
    void confirmAfterReject_conflict409_stateNotFlipped() {
        HumanTicket t = pendingTicket("s1");
        controller.reject(t.id());

        UnifiedResponse resp = controller.confirm(t.id()); // 反向决议

        assertThat(resp.code()).isEqualTo(ErrorCode.CONFLICT.code()); // 409
        assertThat(service.findById(t.id()).orElseThrow().status()).isEqualTo(HumanTicket.Status.REJECTED); // 不回翻
    }

    @Test
    void reject_setsRejected_noResumeTriggered() {
        HumanTicket t = pendingTicket("s1");

        HitlTicketSummary summary = (HitlTicketSummary) controller.reject(t.id()).data();

        assertThat(summary.status()).isEqualTo("REJECTED");
        assertThat(summary.resolvedAt()).isEqualTo(Instant.parse("2026-09-08T10:00:00Z"));
        verify(resumeService, never()).resumeAsync(anyString()); // 驳回不触发恢复
    }

    @Test
    void confirm_unknownId_returnsNotFoundCode() {
        UnifiedResponse resp = controller.confirm("unknown-id");

        assertThat(resp.code()).isEqualTo(ErrorCode.NOT_FOUND.code());
        assertThat(resp.data()).isNull();
        // 未知 id 不应改变任何工单状态
        assertThat(service.pendingTickets()).isEmpty();
    }

    @Test
    void tickets_emptyReturnsEmptyList() {
        UnifiedResponse resp = controller.tickets();

        assertThat(resp.code()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        java.util.List<HitlTicketSummary> data = (java.util.List<HitlTicketSummary>) resp.data();
        assertThat(data).isEmpty();
    }

    // ---- 2026-09-18 同日扩展（用户裁决）：业务前置校验 + 显式恢复接口 ----

    @Test
    void confirm_blockedByBusinessGate_409_ticketStaysPending_noResume() {
        HumanTicket t = pendingTicket("s1");
        when(businessGate.check(any())).thenReturn(
                HitlBusinessGate.Decision.block("订单 ORD-001 未支付，不满足退款前提"));

        UnifiedResponse resp = controller.confirm(t.id());

        assertThat(resp.code()).isEqualTo(ErrorCode.CONFLICT.code()); // 409 + 业务原因
        assertThat(service.findById(t.id()).orElseThrow().status()).isEqualTo(HumanTicket.Status.PENDING); // 不决议，可修复后重审
        verify(resumeService, never()).resumeAsync(anyString()); // 不触发恢复
    }

    @Test
    void reject_skipsBusinessGate_alwaysAllowed() {
        HumanTicket t = pendingTicket("s1");

        UnifiedResponse resp = controller.reject(t.id());

        assertThat(resp.code()).isEqualTo(0); // 驳回不受业务门约束（拒绝永远安全）
        verify(businessGate, never()).check(any());
    }

    @Test
    void resumeEndpoint_approvedTicket_redrivesAsyncResume() {
        HumanTicket t = pendingTicket("s1");
        controller.confirm(t.id()); // 首次批准（触发第 1 次恢复）

        UnifiedResponse resp = controller.resume(t.id()); // 显式恢复接口重驱

        assertThat(resp.code()).isEqualTo(0);
        verify(resumeService, times(2)).resumeAsync(t.id());
    }

    @Test
    void resumeEndpoint_pendingTicket_conflict409() {
        HumanTicket t = pendingTicket("s1"); // 仍 PENDING

        UnifiedResponse resp = controller.resume(t.id());

        assertThat(resp.code()).isEqualTo(ErrorCode.CONFLICT.code());
        verify(resumeService, never()).resumeAsync(anyString());
    }

    @Test
    void resumeEndpoint_unknownId_404() {
        UnifiedResponse resp = controller.resume("no-such-ticket");

        assertThat(resp.code()).isEqualTo(ErrorCode.NOT_FOUND.code());
        verify(resumeService, never()).resumeAsync(anyString());
    }

    // ---- 决议=事件（提交制 2026-09-20）：工作流审批单（wfa: 键）纯状态变更 + 业务执行挂钩 ----

    @Test
    void confirm_workflowApprovalTicket_triggersBusinessExecutor_noResume() {
        // 提交制（用户裁决：业务流程是业务系统内部运作）：批准=触发业务执行 mock，不走检查点恢复
        HumanTicket t = workflowApprovalTicket("wfa:REFUND:ORD-001");

        UnifiedResponse resp = controller.confirm(t.id());

        assertThat(resp.code()).isEqualTo(0);
        verify(businessExecutor).onApproved(any(HumanTicket.class)); // 业务系统消费批准事件
        verify(resumeService, never()).resumeAsync(anyString());     // 工作流单无检查点恢复
        assertThat(service.findById(t.id()).orElseThrow().status()).isEqualTo(HumanTicket.Status.APPROVED);
    }

    @Test
    void reject_workflowApprovalTicket_noExecutor_noResume() {
        // 驳回=纯状态变更（不触发业务执行）
        HumanTicket t = workflowApprovalTicket("wfa:RETURN:ORD-002");

        UnifiedResponse resp = controller.reject(t.id());

        assertThat(resp.code()).isEqualTo(0);
        verify(businessExecutor, never()).onApproved(any(HumanTicket.class));
        verify(resumeService, never()).resumeAsync(anyString());
    }

    @Test
    void confirmRepeat_workflowApprovalTicket_executorTriggeredOnce() {
        // 决议幂等：IDEMPOTENT_REPEAT 不重复触发业务执行
        HumanTicket t = workflowApprovalTicket("wfa:REFUND:ORD-001");
        controller.confirm(t.id());

        UnifiedResponse resp = controller.confirm(t.id()); // 双击/重放

        assertThat(resp.code()).isEqualTo(0);
        verify(businessExecutor, times(1)).onApproved(any(HumanTicket.class));
    }

    @Test
    void confirm_workflowApprovalTicket_executorAbsent_stateStillTransitions() {
        // 业务执行器未装配（app.workflow.enabled=false）→ 决议本身照常（状态变更不受影响）
        HumanTicket t = workflowApprovalTicket("wfa:REFUND:ORD-001");
        when(businessExecutorProvider.getIfAvailable()).thenReturn(null);

        UnifiedResponse resp = controller.confirm(t.id());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(service.findById(t.id()).orElseThrow().status()).isEqualTo(HumanTicket.Status.APPROVED);
        verify(resumeService, never()).resumeAsync(anyString());
    }

    @Test
    void confirm_hitlCheckpointTicket_triggersResume_noExecutor() {
        // 对照组：HITL 检查点单（无幂等键）批准 → 原异步恢复路径，不触发业务执行
        HumanTicket t = pendingTicket("s1");

        UnifiedResponse resp = controller.confirm(t.id());

        assertThat(resp.code()).isEqualTo(0);
        verify(resumeService).resumeAsync(t.id());
        verify(businessExecutor, never()).onApproved(any(HumanTicket.class));
    }

    @Test
    void resumeEndpoint_workflowApprovalTicket_conflict() {
        // 工作流审批单无检查点恢复入口：决议即事件（提交制），resume 显式 409
        HumanTicket t = workflowApprovalTicket("wfa:REFUND:ORD-001");
        service.resolve(t.id(), HumanTicket.Status.APPROVED, Instant.parse("2026-09-08T09:30:00Z"));

        UnifiedResponse resp = controller.resume(t.id());

        assertThat(resp.code()).isEqualTo(ErrorCode.CONFLICT.code());
        verify(resumeService, never()).resumeAsync(anyString());
    }
}
