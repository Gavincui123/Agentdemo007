package com.agentdemo007.feedback;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 反馈采集器测试（Phase 15·T67 微调闭环数据层）。
 *
 * <p>{@link FeedbackCollector} 把 {@link FeedbackRequest}（在线反馈 + 当轮对话上下文）
 * 组装为 {@link TrainingSample} 落入 {@link OfflineDataPool}；时钟可注入保证 collectedAt 确定性；
 * 落池异常吞而不抛（②降级：反馈是副信道，不影响主链路），返回 false。
 */
class FeedbackCollectorTest {

    private static final OffsetDateTime FIXED = OffsetDateTime.of(2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);

    private FeedbackRequest request(String traceId) {
        return new FeedbackRequest(traceId, "sess-1", "你好", "您好",
                FeedbackLabel.POSITIVE, "很满意");
    }

    @Test
    void collect_buildsSampleAndStores_returnsTrue() {
        InMemoryOfflineDataPool pool = new InMemoryOfflineDataPool();
        FeedbackCollector collector = new FeedbackCollector(pool, () -> FIXED);

        boolean collected = collector.collect(request("t-1"));

        assertThat(collected).isTrue();
        List<TrainingSample> drained = pool.drain();
        assertThat(drained).hasSize(1);
        TrainingSample s = drained.get(0);
        assertThat(s.traceId()).isEqualTo("t-1");
        assertThat(s.sessionId()).isEqualTo("sess-1");
        assertThat(s.prompt()).isEqualTo("你好");
        assertThat(s.reply()).isEqualTo("您好");
        assertThat(s.label()).isEqualTo(FeedbackLabel.POSITIVE);
        assertThat(s.comment()).isEqualTo("很满意");
        assertThat(s.collectedAt()).isEqualTo(FIXED); // 注入时钟确定性
    }

    @Test
    void collect_storeThrows_returnsFalse_bestEffort_doesNotThrow() {
        // 落池异常（模拟存储不可达）——②降级：吞而不抛，返回 false
        OfflineDataPool throwing = new OfflineDataPool() {
            @Override
            public void store(TrainingSample sample) {
                throw new RuntimeException("存储不可达");
            }

            @Override
            public List<TrainingSample> drain() {
                return List.of();
            }

            @Override
            public int size() {
                return 0;
            }
        };
        FeedbackCollector collector = new FeedbackCollector(throwing, () -> FIXED);

        assertThatCode(() -> collector.collect(request("t-2")))
                .doesNotThrowAnyException();
        assertThat(collector.collect(request("t-2"))).isFalse();
    }
}
