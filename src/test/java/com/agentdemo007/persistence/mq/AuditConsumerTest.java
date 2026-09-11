package com.agentdemo007.persistence.mq;

import com.agentdemo007.common.trace.TraceId;
import com.agentdemo007.observability.audit.AuditEvent;
import com.agentdemo007.observability.audit.AuditEventType;
import com.agentdemo007.observability.trace.MdcTraceContextPropagator;
import com.agentdemo007.observability.trace.TraceContextPropagator;
import com.agentdemo007.persistence.entity.AuditEventEntity;
import com.agentdemo007.persistence.repository.AuditEventRepository;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.io.IOException;
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
 * Phase 13 审计事件消费者测评。
 *
 * <p>{@link AuditConsumer} 消费 {@code audit.event} 队列——手动 ack 模式：成功落库→{@code basicAck}，
 * 持久化失败→{@code basicNack(requeue=false)} 死信到 DLQ。关键事件全量留痕（§5.11），
 * 失败不阻塞消费（§5.12）。单测 mock {@link Channel}+{@link AuditEventRepository} 验证语义 + 载荷→实体映射。
 */
class AuditConsumerTest {

    private final AuditEventRepository repository = mock(AuditEventRepository.class);
    private final Channel channel = mock(Channel.class);
    private final AuditConsumer consumer = new AuditConsumer(repository);
    private final TraceContextPropagator propagator = new MdcTraceContextPropagator();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void onAuditEvent_persistsMappedEntityAndAcks() throws IOException {
        AuditEvent event = AuditEvent.of(AuditEventType.INJECTION, "trace-2", "sess-2", "注入命中");
        when(repository.save(any(AuditEventEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        consumer.onAuditEvent(event, channel, 11L, null);

        ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(AuditEventType.INJECTION);
        assertThat(captor.getValue().getTraceId()).isEqualTo("trace-2");
        assertThat(captor.getValue().getDetail()).isEqualTo("注入命中");
        verify(channel).basicAck(11L, false);
    }

    @Test
    void onAuditEvent_nacksToDlqOnPersistFailure_doesNotBlock() throws IOException {
        AuditEvent event = AuditEvent.of(AuditEventType.TOOL_FAILURE, "t", "s", "工具失败");
        when(repository.save(any(AuditEventEntity.class))).thenThrow(new RuntimeException("DB 不可用"));

        assertThatCode(() -> consumer.onAuditEvent(event, channel, 9L, null))
                .doesNotThrowAnyException();

        verify(channel).basicNack(9L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void onAuditEvent_restoresProducerTraceIdDuringPersist() throws IOException {
        String traceId = "0af7651916cd43dd831469c8b1c56dc0";
        String traceparent = "00-" + traceId + "-e9f5a2b1074d4e1f-01";
        AuditEventRepository capturingRepo = mock(AuditEventRepository.class);
        AtomicReference<String> seenTrace = new AtomicReference<>();
        when(capturingRepo.save(any(AuditEventEntity.class))).thenAnswer(inv -> {
            seenTrace.set(TraceId.current());
            return inv.getArgument(0);
        });
        AuditConsumer tracingConsumer = new AuditConsumer(capturingRepo, propagator);
        AuditEvent event = AuditEvent.of(AuditEventType.INJECTION, traceId, "sess-9", "注入命中");

        tracingConsumer.onAuditEvent(event, channel, 11L, traceparent);

        assertThat(seenTrace.get()).isEqualTo(traceId); // 跨进程 traceId 不断
        verify(channel).basicAck(11L, false);
        assertThat(MDC.get(TraceId.MDC_KEY)).isNull(); // scope 关闭后还原
    }
}
