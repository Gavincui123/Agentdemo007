package com.agentdemo007.gate;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 访问闸口服务单测（2026-09-18 部署闸门）。
 *
 * <p>规则热更新（Nacos 回调包级 seam 直驱：全量/部分合并/坏 JSON 保留现值）+ 额度语义
 * （超限拒绝、按 IP 隔离、跨自然日重置——固定 Clock 换日期钉重置）。
 * nacos=null 构造（本地规则路径），ConfigService 交互属 {@link EvalContentResolverTest} 同款桩场景，
 * 此处聚焦规则与额度本体。
 */
class AccessGateServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 可推进的固定时区 Clock：测试跨自然日重置。 */
    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now;
        final ZoneId zone = ZONE;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zoneId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static GateRule rule(boolean enabled, String code, int limit) {
        return new GateRule(enabled, code, limit, "请输入口令", "今日已用完");
    }

    private static AccessGateService service(GateRule initial, MutableClock clock) {
        return new AccessGateService(initial, clock, null, null, null);
    }

    // ---- 规则热更新 ----

    @Test
    void handleConfig_fullJson_replacesRule() {
        AccessGateService s = service(rule(false, "old", 5), mutableClock());
        s.handleConfig("{\"enabled\":true,\"accessCode\":\"new-code\",\"dailyLimit\":9}");

        GateRule current = s.current();
        assertThat(current.active()).isTrue();
        assertThat(current.accessCode()).isEqualTo("new-code");
        assertThat(current.dailyLimit()).isEqualTo(9);
        // 未发的话术字段沿用当前生效值（部分更新语义）
        assertThat(current.requiredMessage()).isEqualTo("请输入口令");
    }

    @Test
    void handleConfig_partialJson_mergesOverCurrent() {
        AccessGateService s = service(rule(true, "keep-me", 20), mutableClock());

        s.handleConfig("{\"dailyLimit\":3}"); // 只改限额

        assertThat(s.current().enabled()).isTrue();
        assertThat(s.current().accessCode()).isEqualTo("keep-me");
        assertThat(s.current().dailyLimit()).isEqualTo(3);
    }

    @Test
    void handleConfig_badJson_keepsCurrent() {
        AccessGateService s = service(rule(true, "stable", 20), mutableClock());

        s.handleConfig("not json");

        assertThat(s.current().accessCode()).isEqualTo("stable");
        assertThat(s.current().dailyLimit()).isEqualTo(20);
    }

    // ---- 额度语义 ----

    @Test
    void tryAcquire_withinLimit_allowed_overLimit_rejected() {
        AccessGateService s = service(rule(true, "c", 2), mutableClock());

        assertThat(s.tryAcquire("1.1.1.1", 2)).isTrue();
        assertThat(s.tryAcquire("1.1.1.1", 2)).isTrue();
        assertThat(s.tryAcquire("1.1.1.1", 2)).isFalse(); // 第 3 轮超限
    }

    @Test
    void tryAcquire_perIpIsolated() {
        AccessGateService s = service(rule(true, "c", 1), mutableClock());

        assertThat(s.tryAcquire("1.1.1.1", 1)).isTrue();
        assertThat(s.tryAcquire("2.2.2.2", 1)).isTrue(); // 另一 IP 不受影响
        assertThat(s.tryAcquire("1.1.1.1", 1)).isFalse();
    }

    @Test
    void tryAcquire_dayRollover_resetsQuota() {
        MutableClock clock = mutableClock();
        AccessGateService s = service(rule(true, "c", 1), clock);

        assertThat(s.tryAcquire("1.1.1.1", 1)).isTrue();
        assertThat(s.tryAcquire("1.1.1.1", 1)).isFalse();
        clock.advance(Duration.ofHours(25)); // 跨自然日

        assertThat(s.tryAcquire("1.1.1.1", 1)).isTrue(); // 新的一天额度重置
    }

    @Test
    void remaining_reportsQuotaLeft_withoutConsuming() {
        AccessGateService s = service(rule(true, "c", 3), mutableClock());

        assertThat(s.remaining("1.1.1.1", 3)).isEqualTo(3); // 未用满额不消耗
        s.tryAcquire("1.1.1.1", 3);
        assertThat(s.remaining("1.1.1.1", 3)).isEqualTo(2);
        assertThat(s.remaining("2.2.2.2", 3)).isEqualTo(3);
    }

    // ---- 口令比对 ----

    @Test
    void codeMatches_constantTimeSemantics() {
        assertThat(AccessGateService.codeMatches("abc", "abc")).isTrue();
        assertThat(AccessGateService.codeMatches("abc", "abd")).isFalse();
        assertThat(AccessGateService.codeMatches(null, "abc")).isFalse();
        assertThat(AccessGateService.codeMatches("abc", "")).isFalse(); // 期望口令未配置 fail-closed
        assertThat(AccessGateService.codeMatches("abc", null)).isFalse();
    }

    private static MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-09-18T00:00:00Z"));
    }
}
