package com.agentdemo007.gateway.core;

import com.agentdemo007.gateway.config.FlowControlPolicy;
import com.agentdemo007.gateway.exception.RateLimitExceededException;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Token 预算 / 速率关卡（{@code GatewayInterceptor} 的预算校验实现）。
 *
 * <p>两阶段：{@code check} 在执行前校验速率与日 Token 配额，超限抛
 * {@link RateLimitExceededException}（零 LLM）；{@code record} 在执行后记账。
 * 滑动窗口按 {@link FlowControlPolicy#window()} 重置请求计数；Token 日配额按 <b>UTC 日界</b>
 * （{@code epochDay = millis / 86_400_000}）跨天清零——保证"日"配额语义在长生命周期下成立，
 * 不会随进程运行而永久耗尽。
 *
 * <p>时钟可注入（{@link LongSupplier}，epoch 毫秒）保证可测；生产用 {@link System#currentTimeMillis}。
 * 线程安全（{@link AtomicInteger} + volatile 时间戳）。
 */
public class TokenBudgetChecker {

    private static final long DAY_MS = 86_400_000L;

    private final LongSupplier clockMillis;
    private final AtomicInteger requestsInWindow = new AtomicInteger();
    private final AtomicInteger tokensToday = new AtomicInteger();
    private volatile long windowStart;
    private volatile long dayStart;

    /** 生产构造：系统时钟。 */
    public TokenBudgetChecker() {
        this(System::currentTimeMillis);
    }

    /** 测试构造：注入确定性时钟（epoch 毫秒）。 */
    public TokenBudgetChecker(LongSupplier clockMillis) {
        long now = clockMillis.getAsLong();
        this.clockMillis = clockMillis;
        this.windowStart = now;
        this.dayStart = epochDay(now);
    }

    /** 执行前校验：超速率或超日 Token 配额 → 抛 {@link RateLimitExceededException}。 */
    public void check(GatewayRequest request, FlowControlPolicy policy) {
        if (policy == null) return;
        resetIfExpired(policy);
        if (requestsInWindow.get() >= policy.maxRequestsPerSecond()) {
            throw new RateLimitExceededException("请求速率超限：" + requestsInWindow.get()
                    + " >= " + policy.maxRequestsPerSecond());
        }
        if (tokensToday.get() >= policy.maxTokensPerDay()) {
            throw new RateLimitExceededException("Token 日配额超限：" + tokensToday.get()
                    + " >= " + policy.maxTokensPerDay());
        }
    }

    /** 执行后记账：累加请求计数与 Token 用量。 */
    public void record(int tokens, FlowControlPolicy policy) {
        if (policy == null) return;
        requestsInWindow.incrementAndGet();
        tokensToday.addAndGet(tokens);
    }

    public int tokensToday() {
        return tokensToday.get();
    }

    public int requestsInWindow() {
        return requestsInWindow.get();
    }

    /** 窗口过期 → 请求计数清零；跨天 → 日 Token 配额清零。 */
    private void resetIfExpired(FlowControlPolicy policy) {
        long now = clockMillis.getAsLong();
        if (now - windowStart >= policy.window().toMillis()) {
            requestsInWindow.set(0);
            windowStart = now;
        }
        long today = epochDay(now);
        if (today != dayStart) {
            tokensToday.set(0);
            dayStart = today;
        }
    }

    private static long epochDay(long millis) {
        return millis / DAY_MS;
    }
}
