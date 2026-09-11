package com.agentdemo007.persistence.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev 兜底消息发布实现（Phase 13）。
 *
 * <p>默认装配（{@code app.rabbitmq.enabled=false} 或缺省，{@code matchIfMissing=true}）——仅记日志、不连 broker、
 * 不抛异常。镜像 Redis 条件装配模式：dev 默认内存兜底保证应用可启动，停 MQ 主接口照常 200（§5.12）。
 *
 * <p>单测无需 Spring 容器：直接 {@code new NoopMessagePublisher()} 验证 {@code publish} 不抛。
 * 测试态需要捕获投递的用例使用独立的测试态 {@code CapturingMessagePublisher} 假实现，不在生产 bean 注入测试态状态。
 */
@Component
@ConditionalOnProperty(prefix = "app.rabbitmq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopMessagePublisher implements MessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopMessagePublisher.class);

    @Override
    public void publish(String routingKey, Object payload) {
        if (log.isDebugEnabled()) {
            log.debug("[NoopMessagePublisher] 投递忽略 routingKey={} payload={}",
                    routingKey,
                    (payload == null) ? "null" : payload.getClass().getSimpleName());
        }
    }
}
