package com.agentdemo007.feedback;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线数据池·内存实现测试（Phase 15·T67 微调闭环数据层）。
 *
 * <p>{@link OfflineDataPool} 是微调闭环的存储 seam：{@code store} 追加训练样本，
 * {@code drain} 取出全部并清空（供 T68 {@code FineTuningPipeline} 拉取）。
 * dev 用 {@link InMemoryOfflineDataPool}（线程安全）；prod 可换 JPA/对象存储实现。
 */
class InMemoryOfflineDataPoolTest {

    private TrainingSample sample(String traceId) {
        return new TrainingSample(traceId, "sess", "prompt", "reply",
                FeedbackLabel.POSITIVE, null, OffsetDateTime.now());
    }

    @Test
    void store_growsSize_thenDrainReturnsAllAndClears() {
        InMemoryOfflineDataPool pool = new InMemoryOfflineDataPool();
        assertThat(pool.size()).isZero();

        pool.store(sample("t1"));
        pool.store(sample("t2"));
        assertThat(pool.size()).isEqualTo(2);

        List<TrainingSample> drained = pool.drain();
        assertThat(drained).hasSize(2);
        assertThat(drained).extracting(TrainingSample::traceId).containsExactlyInAnyOrder("t1", "t2");
        assertThat(pool.size()).isZero(); // drain 清空池
    }

    @Test
    void drain_whenEmpty_returnsEmptyList() {
        InMemoryOfflineDataPool pool = new InMemoryOfflineDataPool();

        List<TrainingSample> drained = pool.drain();

        assertThat(drained).isEmpty();
        assertThat(pool.size()).isZero();
    }
}
