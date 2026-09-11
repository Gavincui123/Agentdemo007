package com.agentdemo007.feedback;

import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反馈控制器测试（Phase 15·T67 微调闭环·在线反馈入口）。
 *
 * <p>{@link FeedbackController} 收口 {@code POST /feedback}：校验入参（traceId/prompt/reply 非空、label 非空 →
 * 400），委托 {@link FeedbackCollector} 采集；采集结果经 {@link FeedbackResponse#collected} 透出，
 * 恒 HTTP 200 + code=0（②降级：落池失败不 5xx，仅 collected=false）。
 */
class FeedbackControllerTest {

    private FeedbackRequest valid() {
        return new FeedbackRequest("t-1", "sess-1", "你好", "您好", FeedbackLabel.POSITIVE, null);
    }

    @Test
    void feedback_validRequest_collectedTrue_andPoolHasSample() {
        InMemoryOfflineDataPool pool = new InMemoryOfflineDataPool();
        FeedbackCollector collector = new FeedbackCollector(pool, () -> OffsetDateTime.now());
        FeedbackController controller = new FeedbackController(collector);

        UnifiedResponse response = controller.feedback(valid());

        assertThat(response.code()).isEqualTo(ErrorCode.SUCCESS.code());
        FeedbackResponse data = (FeedbackResponse) response.data();
        assertThat(data.collected()).isTrue();
        assertThat(pool.size()).isEqualTo(1); // 落池
    }

    @Test
    void feedback_blankTraceIdOrPromptOrReplyOrNullLabel_returns400() {
        InMemoryOfflineDataPool pool = new InMemoryOfflineDataPool();
        FeedbackController controller = new FeedbackController(new FeedbackCollector(pool));

        assertThat(controller.feedback(new FeedbackRequest("", "s", "p", "r", FeedbackLabel.POSITIVE, null)).code())
                .isEqualTo(ErrorCode.BAD_REQUEST.code());
        assertThat(controller.feedback(new FeedbackRequest("t", "s", "", "r", FeedbackLabel.POSITIVE, null)).code())
                .isEqualTo(ErrorCode.BAD_REQUEST.code());
        assertThat(controller.feedback(new FeedbackRequest("t", "s", "p", "", FeedbackLabel.POSITIVE, null)).code())
                .isEqualTo(ErrorCode.BAD_REQUEST.code());
        assertThat(controller.feedback(new FeedbackRequest("t", "s", "p", "r", null, null)).code())
                .isEqualTo(ErrorCode.BAD_REQUEST.code());
        assertThat(controller.feedback(null).code()).isEqualTo(ErrorCode.BAD_REQUEST.code());
        assertThat(pool.size()).isZero(); // 违例不入池
    }

    @Test
    void feedback_storeFails_returnsCode0CollectedFalse_doesNotThrow() {
        // 落池失败（模拟存储不可达）——②降级：不 5xx，collected=false
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
        FeedbackController controller = new FeedbackController(new FeedbackCollector(throwing, () -> OffsetDateTime.now()));

        UnifiedResponse response = controller.feedback(valid());

        assertThat(response.code()).isEqualTo(ErrorCode.SUCCESS.code()); // 不 5xx
        assertThat(((FeedbackResponse) response.data()).collected()).isFalse();
    }
}
