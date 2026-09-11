package com.agentdemo007.capability.hitl;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HITL 决策器测试（第四层·超时/权限判断）。
 *
 * <p>覆盖 §5.12 HITL 行"超时/权限"：
 * <ul>
 *   <li>带外人工决议（resolver）优先——APPROVED→Confirmed，REJECTED→Denied；</li>
 *   <li>权限不足→Denied（工单挂起）；</li>
 *   <li>超时未确认→Timeout；</li>
 *   <li>带内同步未决议→Pending（无法自执行高风险，交由 HitlStep 短路为 HITL_TIMEOUT 话术）。</li>
 * </ul>
 */
class HitlDecisionTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final Instant CREATED = Instant.parse("2026-09-05T10:00:00Z");

    private final HitlRequest req = new HitlRequest("s", "q", "r", HitlRequest.RISK_HIGH);
    private final HumanTicket ticket = new HumanTicket("t1", "s", "q", "r",
            HumanTicket.Status.PENDING, CREATED, null);

    private HitlDecision decision(DecisionResolver resolver, PermissionChecker permission) {
        return new HitlDecision(TIMEOUT, resolver, permission);
    }

    @Test
    void withinTimeout_permitted_returnsPending() {
        HitlDecision d = decision(DecisionResolver.none(), PermissionChecker.alwaysPermitted());
        Instant now = CREATED.plus(Duration.ofMinutes(4));

        HitlDecision.Outcome outcome = d.decide(req, ticket, now);

        assertThat(outcome).isInstanceOf(HitlDecision.Pending.class);
    }

    @Test
    void atOrBeyondTimeout_returnsTimeout() {
        HitlDecision d = decision(DecisionResolver.none(), PermissionChecker.alwaysPermitted());

        assertThat(d.decide(req, ticket, CREATED.plus(TIMEOUT)))
                .isInstanceOf(HitlDecision.Timeout.class);
        assertThat(d.decide(req, ticket, CREATED.plus(Duration.ofMinutes(6))))
                .isInstanceOf(HitlDecision.Timeout.class);
    }

    @Test
    void permissionInsufficient_returnsDenied() {
        HitlDecision d = decision(DecisionResolver.none(), (sessionId, risk) -> false);
        Instant now = CREATED.plus(Duration.ofMinutes(1));

        HitlDecision.Outcome outcome = d.decide(req, ticket, now);

        assertThat(outcome).isInstanceOf(HitlDecision.Denied.class);
        assertThat(((HitlDecision.Denied) outcome).reason()).contains("权限");
    }

    @Test
    void resolverApproved_overridesTimeout_returnsConfirmed() {
        DecisionResolver resolver = t -> Optional.of(new HitlDecision.Confirmed("operator-7"));
        HitlDecision d = decision(resolver, PermissionChecker.alwaysPermitted());
        Instant now = CREATED.plus(Duration.ofMinutes(99)); // 远超超时

        HitlDecision.Outcome outcome = d.decide(req, ticket, now);

        assertThat(outcome).isInstanceOf(HitlDecision.Confirmed.class);
        assertThat(((HitlDecision.Confirmed) outcome).approver()).isEqualTo("operator-7");
    }

    @Test
    void resolverRejected_overridesTimeout_returnsDenied() {
        DecisionResolver resolver = t -> Optional.of(new HitlDecision.Denied("人工驳回"));
        HitlDecision d = decision(resolver, (s, r) -> false); // 权限也不足，但人工决议优先
        Instant now = CREATED.plus(Duration.ofMinutes(99));

        HitlDecision.Outcome outcome = d.decide(req, ticket, now);

        assertThat(outcome).isInstanceOf(HitlDecision.Denied.class);
        assertThat(((HitlDecision.Denied) outcome).reason()).isEqualTo("人工驳回");
    }
}
