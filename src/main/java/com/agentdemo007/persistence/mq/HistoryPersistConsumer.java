package com.agentdemo007.persistence.mq;

import com.agentdemo007.config.RabbitMqConfig;
import com.agentdemo007.observability.trace.TraceContextPropagator;
import com.agentdemo007.persistence.entity.ChatTurnEntity;
import com.agentdemo007.persistence.repository.ChatTurnRepository;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 会话持久化消费者（Phase 13·手动 ack 模式）。
 *
 * <p>消费 {@link RabbitMqConfig#SESSION_PERSIST_QUEUE}：成功落库→{@code basicAck}；
 * 持久化失败→{@code basicNack(requeue=false)} 死信到 DLQ（§5.12 不阻塞消费；毒消息进死信不循环）。
 * 单测以 mock {@link Channel}+{@link ChatTurnRepository} 直接调用 {@link #onChatTurn} 验证语义 + 载荷映射。
 *
 * <p>{@code @ConditionalOnProperty(enabled=true)} → dev 默认（{@code enabled=false}/缺省）不装配本类，
 * 无监听容器启动、不连 broker（停 MQ 不影响主接口）。
 */
@Component
@ConditionalOnProperty(prefix = "app.rabbitmq", name = "enabled", havingValue = "true")
public class HistoryPersistConsumer {

    private static final Logger log = LoggerFactory.getLogger(HistoryPersistConsumer.class);

    private final ChatTurnRepository repository;
    private final TraceContextPropagator propagator;

    public HistoryPersistConsumer(ChatTurnRepository repository) {
        this(repository, TraceContextPropagator.NO_OP);
    }

    @Autowired
    public HistoryPersistConsumer(ChatTurnRepository repository, TraceContextPropagator propagator) {
        this.repository = repository;
        this.propagator = propagator;
    }

    /**
     * 消费一轮会话事件并落库。手动 ack：成功→ack；任意异常→nack(requeue=false) 死信，不抛、不阻塞。
     *
     * <p>跨进程 traceId 不断（Phase 15）：入站消息的 {@code traceparent} 头经 {@link TraceContextPropagator}
     * 在消费线程恢复为 current traceId（写 MDC），使落库期间 {@code TraceId.current()} 与生产侧一致。
     * scope 关闭还原 MDC（finally，best-effort，②不抛）。
     *
     * @param event      MQ 载荷（{@link ChatTurnEntity#from(ChatTurnEvent)} 映射落库）
     * @param channel    broker 通道（手动 ack/nack）
     * @param tag        投递标签（{@link AmqpHeaders#DELIVERY_TAG}，监听容器注入；单测直传）
     * @param traceparent W3C trace 头（{@link TraceContextPropagator#TRACEPARENT_HEADER}，缺失时 no-op scope）
     */
    @RabbitListener(queues = RabbitMqConfig.SESSION_PERSIST_QUEUE)
    public void onChatTurn(ChatTurnEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag,
                           @Header(value = TraceContextPropagator.TRACEPARENT_HEADER, required = false) String traceparent) {
        Map<String, String> carrier = new HashMap<>();
        if (traceparent != null) {
            carrier.put(TraceContextPropagator.TRACEPARENT_HEADER, traceparent);
        }
        AutoCloseable scope = propagator.restoreScope(carrier);
        try {
            repository.save(ChatTurnEntity.from(event));
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.warn("会话持久化失败，死信到 DLQ：traceId={} sessionId={}",
                    event.traceId(), event.sessionId(), e);
            try {
                channel.basicNack(tag, false, false);
            } catch (IOException nackEx) {
                log.warn("basicNack 失败 tag={}", tag, nackEx);
            }
        } finally {
            try {
                scope.close();
            } catch (Exception closeEx) {
                // ② best-effort：trace scope 关闭异常（MDC 还原）不影响落库结果
                log.debug("trace scope 关闭异常（忽略）tag={}", tag, closeEx);
            }
        }
    }
}
