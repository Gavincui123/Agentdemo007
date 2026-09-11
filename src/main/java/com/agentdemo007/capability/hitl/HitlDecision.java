package com.agentdemo007.capability.hitl;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * HITL 决策器（第四层·超时/权限判断）。
 *
 * <p>对一张已挂起的 {@link HumanTicket}，据 {@link DecisionResolver}（带外人工决议）、
 * {@link PermissionChecker}（权限）与超时阈值产出决策（§5.12 HITL 行"超时/权限"）：
 * <ol>
 *   <li>带外人工已决议 → 返回该决议（{@link Confirmed}/{@link Denied}），人工优先；</li>
 *   <li>权限不足 → {@link Denied}（工单挂起 + 审计，§5.4.3）；</li>
 *   <li>超时未确认（{@code now >= createdAt + timeout}）→ {@link Timeout}；</li>
 *   <li>带内同步未决议 → {@link Pending}（无法自执行高风险，由 {@code HitlStep} 短路为 HITL_TIMEOUT 话术）。</li>
 * </ol>
 * 非 {@link Confirmed} 终态均由 {@code HitlStep} 收口为 {@code ShortCircuit(HITL_TIMEOUT)} + 工单，
 * 不自动执行高风险动作、不阻塞主链路（立即返回话术，不挂起请求）。
 *
 * <p>{@code timeout} 与 {@code now}（由 {@code HitlStep} 传入 {@code Instant.now()}）均为可注入边界，
 * 便于单测精确验证超时临界。
 */
public class HitlDecision {

    private final Duration timeout;
    private final DecisionResolver resolver;
    private final PermissionChecker permissionChecker;

    public HitlDecision(Duration timeout, DecisionResolver resolver, PermissionChecker permissionChecker) {
        this.timeout = timeout;
        this.resolver = (resolver != null) ? resolver : DecisionResolver.none();
        this.permissionChecker = (permissionChecker != null) ? permissionChecker : PermissionChecker.alwaysPermitted();
    }

    public Outcome decide(HitlRequest request, HumanTicket ticket, Instant now) {
        // 1. 带外人工决议优先（prod：查询工单存储；dev：空）
        Optional<Outcome> resolved = resolver.resolve(ticket);
        if (resolved.isPresent()) {
            return resolved.get();
        }
        // 2. 权限不足→Denied（工单挂起）
        if (!permissionChecker.permitted(request.sessionId(), request.riskLevel())) {
            return new Denied("权限不足，已挂起工单待人工处理");
        }
        // 3. 超时未确认→Timeout
        if (!now.isBefore(ticket.createdAt().plus(timeout))) {
            return new Timeout();
        }
        // 4. 带内同步未决议→Pending
        return new Pending();
    }

    /** 决策终态（sealed）：Confirmed 放行；其余三态均短路为 HITL_TIMEOUT 话术 + 工单。 */
    public sealed interface Outcome permits Confirmed, Denied, Timeout, Pending {
    }

    /** 人工已确认，可放行继续执行。 */
    public record Confirmed(String approver) implements Outcome {
    }

    /** 人工驳回或权限不足，工单挂起。 */
    public record Denied(String reason) implements Outcome {
    }

    /** 超时未确认。 */
    public record Timeout() implements Outcome {
    }

    /** 带内同步未决议（人工审批异步，单请求内无法等待）。 */
    public record Pending() implements Outcome {
    }
}
