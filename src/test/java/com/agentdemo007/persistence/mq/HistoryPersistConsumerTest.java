package com.agentdemo007.persistence.mq;

import com.agentdemo007.common.trace.TraceId;
import com.agentdemo007.observability.trace.MdcTraceContextPropagator;
import com.agentdemo007.observability.trace.TraceContextPropagator;
import com.agentdemo007.persistence.entity.ChatTurnEntity;
import com.agentdemo007.persistence.repository.ChatTurnRepository;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 13 会话持久化消费者测评。
 *
 * <p>{@link HistoryPersistConsumer} 消费 {@code session.persist} 队列——手动 ack 模式：成功落库→{@code basicAck}，
 * 持久化失败→{@code basicNack(requeue=false)} 死信到 DLQ（§5.12 不阻塞消费；毒消息进死信不循环）。
 * 单测 mock {@link Channel}（broker 依赖）+ {@link ChatTurnRepository}（DB 依赖），验证 ack/nack 语义 + 载荷→实体映射。
 */
class HistoryPersistConsumerTest {

    private final ChatTurnRepository repository = mock(ChatTurnRepository.class);
    private final Channel channel = mock(Channel.class);
    private final HistoryPersistConsumer consumer = new HistoryPersistConsumer(repository);
    private final TraceContextPropagator propagator = new MdcTraceContextPropagator();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void onChatTurn_persistsMappedEntityAndAcks() throws IOException {
        ChatTurnEvent event = new ChatTurnEvent(
                "trace-1", "sess-1", "你好", "您好",
                "REASONING", false, null, OffsetDateTime.now());
        when(repository.save(any(ChatTurnEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        consumer.onChatTurn(event, channel, 42L, null);

        ArgumentCaptor<ChatTurnEntity> captor = ArgumentCaptor.forClass(ChatTurnEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTraceId()).isEqualTo("trace-1");
        assertThat(captor.getValue().getSessionId()).isEqualTo("sess-1");
        assertThat(captor.getValue().getIntent()).isEqualTo("REASONING");
        verify(channel).basicAck(42L, false);
    }

    @Test
    void onChatTurn_nacksToDlqOnPersistFailure_doesNotBlock() throws IOException {
        ChatTurnEvent event = new ChatTurnEvent(
                "t", "s", "你好", "您好",
                null, false, null, OffsetDateTime.now());
        when(repository.save(any(ChatTurnEntity.class))).thenThrow(new RuntimeException("DB 不可用"));

        assertThatCode(() -> consumer.onChatTurn(event, channel, 7L, null))
                .doesNotThrowAnyException();

        // requeue=false → 死信到 DLQ，不阻塞后续消费
        verify(channel).basicNack(7L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void onChatTurn_restoresProducerTraceIdDuringPersist() throws IOException {
        String traceId = "0af7651916cd43dd831469c8b1c56dc0";
        String traceparent = "00-" + traceId + "-e9f5a2b1074d4e1f-01";
        ChatTurnRepository capturingRepo = mock(ChatTurnRepository.class);
        AtomicReference<String> seenTrace = new AtomicReference<>();
        when(capturingRepo.save(any(ChatTurnEntity.class))).thenAnswer(inv -> {
            seenTrace.set(TraceId.current()); // 消费线程恢复后的 current traceId
            return inv.getArgument(0);
        });
        HistoryPersistConsumer tracingConsumer = new HistoryPersistConsumer(capturingRepo, propagator);
        ChatTurnEvent event = new ChatTurnEvent(
                traceId, "sess-9", "你好", "您好", null, false, null, OffsetDateTime.now());

        tracingConsumer.onChatTurn(event, channel, 42L, traceparent);

        assertThat(seenTrace.get()).isEqualTo(traceId); // 跨进程 traceId 不断
        verify(channel).basicAck(42L, false);
        assertThat(MDC.get(TraceId.MDC_KEY)).isNull(); // scope 关闭后还原，不污染
    }
}
