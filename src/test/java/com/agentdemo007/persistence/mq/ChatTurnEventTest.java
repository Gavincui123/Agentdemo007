package com.agentdemo007.persistence.mq;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.intent.Intent;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 会话轮次 MQ 载荷测评。
 *
 * <p>{@link ChatTurnEvent} 是会话持久化的 MQ 消息体——由终端后置钩子 {@code ChatTurnFinalizer}
 * 在流水线结束后从 {@link PipelineContext} + {@link PipelineResult} 组装，投递到会话持久化队列，
 * 消费者异步落库。强类型 record（§5.14：禁止各步私造 Map 互相传参）。
 */
class ChatTurnEventTest {

    @Test
    void from_buildsFromContextAndProceedResult() {
        PipelineContext ctx = new PipelineContext("trace-9", "sess-9", "Q3 销售额多少");
        ctx.setIntent(Intent.REASONING);

        PipelineResult result = PipelineResult.ok("Q3 销售额为 1.2 亿元。");

        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        ChatTurnEvent event = ChatTurnEvent.from(ctx, result);

        assertThat(event.traceId()).isEqualTo("trace-9");
        assertThat(event.sessionId()).isEqualTo("sess-9");
        assertThat(event.rawInput()).isEqualTo("Q3 销售额多少");
        assertThat(event.finalReply()).isEqualTo("Q3 销售额为 1.2 亿元。");
        assertThat(event.intent()).isEqualTo("REASONING");
        assertThat(event.degraded()).isFalse();
        assertThat(event.scenario()).isNull();
        assertThat(event.timestamp()).isAfterOrEqualTo(before);
    }

    @Test
    void from_buildsFromDegradedResult_carriesScenario() {
        PipelineContext ctx = new PipelineContext("trace-1", "sess-1", "你好");
        // intent 未识别（null）
        PipelineResult result = PipelineResult.shortCircuit("我暂时无法回应，请稍后重试。",
                com.agentdemo007.common.degradation.DegradationScenario.MODEL_DOWN);

        ChatTurnEvent event = ChatTurnEvent.from(ctx, result);

        assertThat(event.intent()).isNull();
        assertThat(event.degraded()).isTrue();
        assertThat(event.scenario()).isEqualTo("MODEL_DOWN");
        assertThat(event.finalReply()).isEqualTo("我暂时无法回应，请稍后重试。");
    }
}
