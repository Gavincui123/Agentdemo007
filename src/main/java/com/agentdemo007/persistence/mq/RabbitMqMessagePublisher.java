package com.agentdemo007.persistence.mq;

import com.agentdemo007.observability.trace.TraceContextPropagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 真实 AMQP 消息发布实现（Phase 13·生产装配）。
 *
 * <p>经 {@code RabbitTemplate.convertAndSend(exchange, routingKey, payload)} 投递，由
 * {@code JacksonJsonMessageConverter}（Jackson 3，{@code tools.jackson}）序列化强类型 record 载荷。
 * 由 {@code RabbitMqConfig} 在 {@code app.rabbitmq.enabled=true} 时条件装配。
 *
 * <p>MQ 异常吞而不抛（§5.12 能跑通&gt;完美）：{@code AmqpException}（broker 不可用、超时等）被 catch
 * 仅记 warn 日志，不向调用者传播——停 MQ 主接口照常 200。终端后置钩子 {@code ChatTurnFinalizer}
 * 另有兜底 catch，防御纵深。
 */
public class RabbitMqMessagePublisher implements MessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqMessagePublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final TraceContextPropagator propagator;

    public RabbitMqMessagePublisher(RabbitTemplate rabbitTemplate, String exchange) {
        this(rabbitTemplate, exchange, TraceContextPropagator.NO_OP);
    }

    /** 装配路径（含 trace 传播）：投递时经 {@link MessagePostProcessor} 注入 traceparent 头。 */
    public RabbitMqMessagePublisher(RabbitTemplate rabbitTemplate, String exchange,
                                    TraceContextPropagator propagator) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.propagator = propagator;
    }

    @Override
    public void publish(String routingKey, Object payload) {
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, payload, this::injectTrace);
        } catch (AmqpException e) {
            log.warn("[RabbitMqMessagePublisher] 投递失败 exchange={} routingKey={}: {}",
                    exchange, routingKey, e.getMessage());
            // 吞而不抛（§5.12）：停 MQ 主接口照常 200
        }
    }

    /**
     * 出站消息注入当前 trace 上下文（traceparent 头），跨进程 traceId 不断（Phase 15）。
     * best-effort（②每步降级）：注入失败不抛、不影响投递；carrier 经 {@link TraceContextPropagator}
     * 收口（④统一），prod 委托 OTel W3CTraceContextPropagator（§5.14）。
     */
    private Message injectTrace(Message message) {
        try {
            Map<String, String> carrier = new HashMap<>();
            propagator.inject(carrier);
            carrier.forEach((k, v) -> message.getMessageProperties().setHeader(k, v));
        } catch (Exception ignored) {
            // ② best-effort：trace 注入失败不影响投递
        }
        return message;
    }
}
