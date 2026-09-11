package com.agentdemo007.admin;

import com.agentdemo007.capability.hitl.HumanTicket;
import com.agentdemo007.capability.hitl.HumanTicketService;
import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/**
 * 管理台·HITL 工单端点（Phase 19·T97）。
 *
 * <ul>
 *   <li>{@code GET  /admin/hitl/tickets} — 列 PENDING 工单（待审批）。</li>
 *   <li>{@code POST /admin/hitl/tickets/{id}/confirm} — 流转到 APPROVED。</li>
 *   <li>{@code POST /admin/hitl/tickets/{id}/reject}  — 流转到 REJECTED。</li>
 * </ul>
 *
 * <p>未知 id → {@link ErrorCode#NOT_FOUND}（②降级不 5xx，同形 HTTP 200 + code 体内表达，与兄弟端点同形）。
 * {@code resolvedAt} 取注入式 {@link Clock}（可测，匹配 {@code AlertRuleEvaluator} 注入时钟模式）。
 * 对外统一 {@link UnifiedResponse}；鉴权在 T103 收口（{@code /admin/*} 应受保护，此处先开放便于联调）。
 */
@RestController
@RequestMapping("/admin/hitl")
public class AdminHitlController {

    private final HumanTicketService ticketService;
    private final Clock clock;

    public AdminHitlController(HumanTicketService ticketService, Clock clock) {
        this.ticketService = ticketService;
        this.clock = clock;
    }

    @GetMapping("/tickets")
    public UnifiedResponse tickets() {
        List<HitlTicketSummary> summaries = ticketService.pendingTickets().stream()
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

    private UnifiedResponse resolve(String id, HumanTicket.Status decision) {
        if (ticketService.findById(id).isEmpty()) {
            return UnifiedResponse.error(ErrorCode.NOT_FOUND, "工单不存在：" + id);
        }
        ticketService.resolve(id, decision, clock.instant());
        return UnifiedResponse.success(toSummary(ticketService.findById(id).orElseThrow()));
    }

    private static HitlTicketSummary toSummary(HumanTicket t) {
        return new HitlTicketSummary(t.id(), t.sessionId(), t.query(), t.reason(),
                t.status().name(), t.createdAt(), t.resolvedAt());
    }
}
