package com.agentdemo007.admin;

import com.agentdemo007.capability.hitl.HitlBusinessGate;
import com.agentdemo007.capability.hitl.HumanTicket;
import com.agentdemo007.capability.hitl.HitlResumeService;
import com.agentdemo007.capability.hitl.HumanTicketService;
import com.agentdemo007.capability.workflow.AfterSaleBusinessExecutor;
import com.agentdemo007.capability.workflow.TicketApprovalSubmitter;
import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 管理台·HITL 工单端点（Phase 19·T97；2026-09-18 L2 挂起-恢复扩展）。
 *
 * <ul>
 *   <li>{@code GET  /admin/hitl/tickets} — 列<b>全量</b>工单（PENDING 优先，已决议单留痕带徽章；
 *       内存+DB 持久副本合并，重启不丢历史·2026-09-20）。</li>
 *   <li>{@code POST /admin/hitl/tickets/{id}/confirm} — 流转到 APPROVED；首次转移触发后续动作：
 *       工作流审批单（{@code wfa:} 键）→ 业务系统执行（{@code AfterSaleBusinessExecutor} mock 留痕，
 *       <b>决议只是状态变更事件，无请求内唤醒</b>·2026-09-20 提交制）；HITL 检查点单 → 异步恢复执行
 *       （业务前置校验 + 漂移校验 + 消费 checkpoint + 只跑 610 后段步骤 + 会话历史落库）。</li>
 *   <li>{@code POST /admin/hitl/tickets/{id}/reject}  — 流转到 REJECTED（不触发恢复）。</li>
 *   <li>{@code POST /admin/hitl/tickets/{id}/resume}  — <b>显式恢复接口</b>：对已 APPROVED 工单重驱
 *       恢复执行（业务校验修复后重试/幂等重放）；恢复前置校验全在 {@link HitlResumeService} 内收口。</li>
 * </ul>
 *
 * <p><b>业务前置校验（用户裁决：工单状态收尾不自证，结合业务）</b>：confirm 放行前经
 * {@link HitlBusinessGate} 对账订单真实状态（退款查支付状态/退货查物流状态）——不满足则
 * <b>不决议</b>（工单保持 PENDING），返回 {@link ErrorCode#CONFLICT}(409) + 业务原因；
 * 管理员修复数据（补录订单/等物流签收）后重新 confirm 即可。reject/决议幂等不受业务门影响。
 *
 * <p>决议幂等（状态机守卫，防重复/反向提交）：
 * <ul>
 *   <li>PENDING → 目标终态：转移成功（confirm 额外触发恢复）；</li>
 *   <li>同终态重复提交（双击/并发重放）：幂等成功返回当前工单，<b>不重复触发恢复</b>；</li>
 *   <li>异终态重复提交（先批准后驳回/超时后决议）：{@link ErrorCode#CONFLICT}(409)，状态不回翻。</li>
 * </ul>
 * 未知 id → {@link ErrorCode#NOT_FOUND}（②降级不 5xx，同形 HTTP 200 + code 体内表达，与兄弟端点同形）。
 * {@code resolvedAt} 取注入式 {@link Clock}（可测，匹配 {@code AlertRuleEvaluator} 注入时钟模式）。
 * 对外统一 {@link UnifiedResponse}；鉴权经 {@code AdminAuthInterceptor}（/admin/**）。
 */
@RestController
@RequestMapping("/admin/hitl")
public class AdminHitlController {

    private final HumanTicketService ticketService;
    private final HitlResumeService resumeService;
    private final HitlBusinessGate businessGate;
    private final Clock clock;
    /**
     * 售后业务执行器（提交制 2026-09-20·用户裁决：业务流程是业务系统内部运作）：工作流审批工单
     * （幂等键 {@code wfa:} 前缀）决议为 APPROVED 后触发——业务系统的事件消费入口，非 Agent 链路；
     * 决议本身只是工单状态变更，<b>不存在请求内唤醒/检查点恢复</b>（桥机制已退役，超时不可能影响决议）。
     * Agent 侧对结果的感知走工单状态查询工具。null=未装配（app.workflow.enabled=false）。
     */
    private final ObjectProvider<AfterSaleBusinessExecutor> businessExecutor;

    public AdminHitlController(HumanTicketService ticketService, HitlResumeService resumeService,
                               HitlBusinessGate businessGate, Clock clock,
                               ObjectProvider<AfterSaleBusinessExecutor> businessExecutor) {
        this.ticketService = ticketService;
        this.resumeService = resumeService;
        this.businessGate = businessGate;
        this.clock = clock;
        this.businessExecutor = businessExecutor;
    }

    /**
     * 列工单（<b>含已决议</b>·2026-09-20）：批准/驳回是事件、工单是唯一审计留痕（提交制·用户裁决），
     * 决议后工单不从控制台消失——PENDING 优先展示，已决议单带状态徽章留痕（前端按状态渲染，
     * 操作按钮仅 PENDING）。内存 + DB 持久副本合并，重启不丢历史。
     */
    @GetMapping("/tickets")
    public UnifiedResponse tickets() {
        List<HitlTicketSummary> summaries = ticketService.allTickets().stream()
                .map(AdminHitlController::toSummary)
                .toList();
        return UnifiedResponse.success(summaries);
    }

    @PostMapping("/tickets/{id}/confirm")
    public UnifiedResponse confirm(@PathVariable String id) {
        return resolve(id, HumanTicket.Status.APPROVED);
    }

    @PostMapping("/tickets/{id}/reject")
    public UnifiedResponse reject(@PathVariable String id) {
        return resolve(id, HumanTicket.Status.REJECTED);
    }

    /** 显式恢复接口：APPROVED 工单重驱恢复执行（业务校验/漂移校验/CAS 在恢复服务内收口）。 */
    @PostMapping("/tickets/{id}/resume")
    public UnifiedResponse resume(@PathVariable String id) {
        Optional<HumanTicket> ticket = ticketService.findById(id);
        if (ticket.isEmpty()) {
            return UnifiedResponse.error(ErrorCode.NOT_FOUND, "工单不存在：" + id);
        }
        if (ticket.get().status() != HumanTicket.Status.APPROVED) {
            return UnifiedResponse.error(ErrorCode.CONFLICT,
                    "工单非 APPROVED（当前 " + ticket.get().status().name() + "），无恢复入口");
        }
        if (isWorkflowApprovalTicket(ticket.get())) {
            return UnifiedResponse.error(ErrorCode.CONFLICT,
                    "工作流审批单无检查点恢复入口：confirm/reject 决议即驱动工作流继续");
        }
        resumeService.resumeAsync(id); // 异步重驱（漂移/业务/CAS 校验在服务内收口，重复提交幂等拒绝）
        return UnifiedResponse.success(toSummary(ticket.get()));
    }

    private UnifiedResponse resolve(String id, HumanTicket.Status decision) {
        Optional<HumanTicket> ticket = ticketService.findById(id);
        if (ticket.isEmpty()) {
            return UnifiedResponse.error(ErrorCode.NOT_FOUND, "工单不存在：" + id);
        }
        if (decision == HumanTicket.Status.APPROVED) {
            // 业务前置校验（用户裁决）：收尾不自证——业务不满足则不决议，工单保持 PENDING 可重审
            // （工作流审批单同受此门约束：退款对账支付状态/退货对账物流状态，wfa: 幂等键同形解析）
            HitlBusinessGate.Decision biz = businessGate.check(ticket.get());
            if (!biz.allowed()) {
                return UnifiedResponse.error(ErrorCode.CONFLICT, "业务校验未通过：" + biz.reason());
            }
        }
        HumanTicketService.ResolveResult result = ticketService.resolve(id, decision, clock.instant());
        return switch (result) {
            case TRANSITIONED -> {
                // 决议=事件（提交制 2026-09-20）：只做工单状态变更——工作流审批单（wfa: 键）批准后触发
                // 业务系统执行（mock 留痕，非 Agent 链路）；HITL 检查点单批准后走原异步恢复。
                // 无桥、无请求内唤醒：等待窗口机制已退役，超时不可能影响决议，客户经工单查询工具获知进度。
                HumanTicket resolved = ticketService.findById(id).orElseThrow();
                if (isWorkflowApprovalTicket(resolved)) {
                    if (decision == HumanTicket.Status.APPROVED) {
                        AfterSaleBusinessExecutor executor = businessExecutor.getIfAvailable();
                        if (executor != null) {
                            executor.onApproved(resolved);
                        }
                    }
                } else if (decision == HumanTicket.Status.APPROVED) {
                    resumeService.resumeAsync(id); // 首次批准 → 异步恢复执行（漂移/业务/CAS 在服务内收口）
                }
                yield UnifiedResponse.success(toSummary(resolved));
            }
            case IDEMPOTENT_REPEAT ->
                    UnifiedResponse.success(toSummary(ticketService.findById(id).orElseThrow())); // 幂等：不重复触发副作用
            case CONFLICT -> UnifiedResponse.error(ErrorCode.CONFLICT,
                    "工单已决议为 " + ticketService.findById(id).map(t -> t.status().name()).orElse("?")
                            + "，不得重复/反向决议");
        };
    }

    /** 工作流审批单判定（幂等键 {@code wfa:} 前缀，{@link TicketApprovalSubmitter#KEY_PREFIX}）。 */
    private static boolean isWorkflowApprovalTicket(HumanTicket ticket) {
        String key = ticket.idempotencyKey();
        return key != null && key.startsWith(TicketApprovalSubmitter.KEY_PREFIX);
    }

    private static HitlTicketSummary toSummary(HumanTicket t) {
        return new HitlTicketSummary(t.id(), t.sessionId(), t.query(), t.reason(),
                t.status().name(), t.createdAt(), t.resolvedAt());
    }
}
