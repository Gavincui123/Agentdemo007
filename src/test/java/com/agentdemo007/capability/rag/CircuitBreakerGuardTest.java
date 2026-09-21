package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RAG 外部通道熔断守卫测试。
 *
 * <p>单次失败即开断路（阈值 1）：冷却期内调用快速失败（不再吃满 HTTP 超时），冷却期满半开放行；
 * 成功记成功并关闭断路器。健康期零影响。
 */
class CircuitBreakerGuardTest {

    @Test
    void success_passesThrough_andStaysClosed() {
        CircuitBreakerGuard guard = new CircuitBreakerGuard("test", 60_000, 30_000);

        assertThat(guard.call(() -> "ok")).isEqualTo("ok");
        assertThat(guard.call(() -> "ok2")).isEqualTo("ok2"); // 健康期零影响
    }

    @Test
    void failure_tripsBreaker_nextCallFailsFast_withoutInvokingAction() {
        CircuitBreakerGuard guard = new CircuitBreakerGuard("test", 60_000, 30_000);
        int[] invocations = {0};

        assertThatThrownBy(() -> guard.call(() -> {
            invocations[0]++;
            throw new IllegalStateException("下游超时");
        })).isInstanceOf(IllegalStateException.class);

        // 断路已开：第二次调用不再触达下游（快速失败），抛熔断异常
        assertThatThrownBy(() -> guard.call(() -> {
            invocations[0]++;
            return "ok";
        })).hasMessageContaining("熔断开启中");
        assertThat(invocations[0]).isEqualTo(1);
    }

    @Test
    void cooldownExpires_halfOpen_probeAllowed() throws Exception {
        CircuitBreakerGuard guard = new CircuitBreakerGuard("test", 60_000, 50);
        assertThatThrownBy(() -> guard.call(() -> { throw new IllegalStateException("挂起"); }))
                .isInstanceOf(IllegalStateException.class);

        // 过冷却期 → 半开放行探测。轮询等待而非固定 sleep：熔断时钟走 System::currentTimeMillis
        // （墙钟），NTP 回拨/调度抖动会让固定 sleep 偶发不足冷却期（2026-09-21 实测一次）。
        // 冷却未过时的拒绝在 allowRequest 内快速失败、不 recordFailure——重试安全、不延冷却。
        String probe = null;
        for (int i = 0; i < 100 && probe == null; i++) {
            Thread.sleep(20);
            try {
                probe = guard.call(() -> "recovered");
            } catch (IllegalStateException expectedDuringCooldown) {
                // 冷却未过，继续等
            }
        }
        assertThat(probe).isEqualTo("recovered");
        assertThat(guard.call(() -> "still-ok")).isEqualTo("still-ok"); // 探测成功 → 关闭
    }
}
