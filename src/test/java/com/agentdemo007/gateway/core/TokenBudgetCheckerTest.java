package com.agentdemo007.gateway.core;

import com.agentdemo007.gateway.config.FlowControlPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Token 预算/速率关卡单测（Phase 4·网关预算校验）。
 *
 * <p>覆盖：速率窗口超限抛 {@link com.agentdemo007.gateway.exception.RateLimitExceededException}、
 * 日 Token 配额超限、窗口过期重置、<b>日界跨天重置</b>（时钟可注入保证确定性）。
 */
class TokenBudgetCheckerTest {

    private static final long DAY_MS = 86_400_000L;

    /** 速率超限 → 抛异常，零 LLM 短路。 */
    @Test
    void check_throwsWhenRateExceeded() {
        AtomicLong now = new AtomicLong(0L);
        FlowControlPolicy policy = new FlowControlPolicy("fc", 2, 200_000, Duration.ofSeconds(1));
        TokenBudgetChecker checker = new TokenBudgetChecker(now::get);

        checker.record(10, policy); // 1 次请求，窗口内未超
        checker.record(10, policy); // 2 次请求，达上限
        // 第 3 次进入 check：窗口内已达上限 → 超限
        assertThatThrownBy(() -> checker.check(anyRequest(policy), policy))
                .isInstanceOf(com.agentdemo007.gateway.exception.RateLimitExceededException.class);
    }

    /** 速率窗口过期 → 请求计数清零，再次放行。 */
    @Test
    void check_resetsAfterWindowExpires() {
        AtomicLong now = new AtomicLong(0L);
        FlowControlPolicy policy = new FlowControlPolicy("fc", 1, 200_000, Duration.ofSeconds(1));
        TokenBudgetChecker checker = new TokenBudgetChecker(now::get);

        checker.record(10, policy); // 窗口内 1 次，达上限
        assertThatThrownBy(() -> checker.check(anyRequest(policy), policy))
                .isInstanceOf(com.agentdemo007.gateway.exception.RateLimitExceededException.class);

        now.addAndGet(Duration.ofSeconds(2).toMillis()); // 越过窗口
        // 窗口重置后不再超限
        checker.check(anyRequest(policy), policy);
        assertThat(checker.requestsInWindow()).isZero();
    }

    /** 日 Token 配额超限 → 抛异常。 */
    @Test
    void check_throwsWhenDailyTokenQuotaExceeded() {
        AtomicLong now = new AtomicLong(0L);
        FlowControlPolicy policy = new FlowControlPolicy("fc", 1000, 100, Duration.ofSeconds(1));
        TokenBudgetChecker checker = new TokenBudgetChecker(now::get);

        checker.record(100, policy); // 当日 100 token，达日上限
        assertThatThrownBy(() -> checker.check(anyRequest(policy), policy))
                .isInstanceOf(com.agentdemo007.gateway.exception.RateLimitExceededException.class);
    }

    /** 跨天（UTC epochDay 变化）→ tokensToday 清零，原已耗尽的日配额重新放行。 */
    @Test
    void check_resetsTokensTodayAcrossDayBoundary() {
        AtomicLong now = new AtomicLong(0L);
        FlowControlPolicy policy = new FlowControlPolicy("fc", 1000, 100, Duration.ofSeconds(60));
        TokenBudgetChecker checker = new TokenBudgetChecker(now::get);

        checker.record(100, policy); // 当日配额耗尽
        assertThatThrownBy(() -> checker.check(anyRequest(policy), policy))
                .isInstanceOf(com.agentdemo007.gateway.exception.RateLimitExceededException.class);
        assertThat(checker.tokensToday()).isEqualTo(100);

        now.addAndGet(DAY_MS); // 跨入次日
        // 日配额跨天重置 → 不再超限
        checker.check(anyRequest(policy), policy);
        assertThat(checker.tokensToday()).isZero();
    }

    /** 无策略（null）→ 直接放行，不校验、不记账。 */
    @Test
    void check_passesWhenPolicyNull() {
        TokenBudgetChecker checker = new TokenBudgetChecker();
        checker.check(anyRequest(null), null); // 不抛
        checker.record(5, null);
    }

    private static GatewayRequest anyRequest(FlowControlPolicy policy) {
        return new GatewayRequest("m", "p", 10,
                new com.agentdemo007.gateway.config.FailoverPolicy.Builder("m").build(),
                policy);
    }
}
