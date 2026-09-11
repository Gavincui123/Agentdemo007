package com.agentdemo007.persistence.mq;

import com.agentdemo007.common.trace.TraceId;
import com.agentdemo007.observability.trace.MdcTraceContextPropagator;
import com.agentdemo007.observability.trace.TraceContextPropagator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Phase 13 真实 AMQP 消息发布实现的纯单元测评。
 *
 * <p>{@link RabbitMqMessagePublisher} 是 {@link MessagePublisher} seam 的生产实现——
 * 经 {@code RabbitTemplate.convertAndSend(exchange, routingKey, payload)} 投递，
 * 由 {@code JacksonJsonMessageConverter}（Jackson 3）序列化强类型 record 载荷。
 *
 * <p>MQ 异常吞而不抛（§5.12 能跑通&gt;完美）：broker 不可用时 {@code AmqpException} 被 catch，
 * 不向调用者传播——停 MQ 主接口照常 200。{@code RabbitTemplate} 为基础设施依赖（需 broker），
 * 故以 Mockito mock 替换（TDD：mock 仅在真实依赖不可构造时使用）。
 */
class RabbitMqMessagePublisherTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final RabbitMqMessagePublisher publisher =
            new RabbitMqMessagePublisher(rabbitTemplate, "agent.exchange");

    private static final String TRACE_ID = "0af7651916cd43dd831469c8b1c56dc0";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void publish_convertAndSendsToConfiguredExchangeAndRoutingKey() {
        ChatTurnEvent payload = new ChatTurnEvent(
                "trace-2", "sess-2", "你好", "您好",
                null, false, null, OffsetDateTime.now());

        publisher.publish("session.persist", payload);

        verify(rabbitTemplate).convertAndSend(
                eq("agent.exchange"), eq("session.persist"), eq(payload), any(MessagePostProcessor.class));
    }

    @Test
    void publish_swallowsAmqpException_doesNotRethrow() {
        doThrow(new AmqpException("broker 不可用")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class));

        assertThatCode(() -> publisher.publish("audit.event", "payload"))
                .doesNotThrowAnyException();
    }

    @Test
    void publish_injectsTraceparentHeaderIntoOutboundMessage() throws Exception {
        MDC.put(TraceId.MDC_KEY, TRACE_ID);
        TraceContextPropagator propagator = new MdcTraceContextPropagator();
        RabbitMqMessagePublisher tracingPublisher =
                new RabbitMqMessagePublisher(rabbitTemplate, "agent.exchange", propagator);
        ChatTurnEvent payload = new ChatTurnEvent(
                TRACE_ID, "sess-9", "你好", "您好", null, false, null, OffsetDateTime.now());

        tracingPublisher.publish("session.persist", payload);

        ArgumentCaptor<MessagePostProcessor> mpp = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
                eq("agent.exchange"), eq("session.persist"), eq(payload), mpp.capture());
        Message processed = mpp.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        String traceparent = (String) processed.getMessageProperties()
                .getHeader(TraceContextPropagator.TRACEPARENT_HEADER);
        assertThat(traceparent).isNotNull();
        assertThat(traceparent).startsWith("00-" + TRACE_ID + "-"); // 出站消息携带 producer 当前 traceId
    }
}
