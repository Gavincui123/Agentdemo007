package com.agentdemo007.persistence.mq;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 测试态消息发布假实现（Phase 13）——捕获 {@link MessagePublisher#publish} 调用供断言，不连 broker。
 *
 * <p>仅存在于测试源码，不注入生产上下文（生产用 {@code NoopMessagePublisher}/真实 {@code RabbitMqMessagePublisher}）。
 * 生产者（{@code HistoryPersistProducer}/{@code AuditProducer}）依赖 seam 而非 {@code RabbitTemplate}，
 * 故单测用本假替换 seam 即可验证路由键 + 载荷透传，无需 broker（§5.14 引擎无关 seam）。
 */
public class CapturingMessagePublisher implements MessagePublisher {

    private final List<Map.Entry<String, Object>> published = new ArrayList<>();

    @Override
    public void publish(String routingKey, Object payload) {
        published.add(Map.entry(routingKey, payload));
    }

    public List<Map.Entry<String, Object>> published() {
        return published;
    }

    public int publishCount() {
        return published.size();
    }

    public String lastRoutingKey() {
        return published.isEmpty() ? null : published.get(published.size() - 1).getKey();
    }

    public Object lastPayload() {
        return published.isEmpty() ? null : published.get(published.size() - 1).getValue();
    }
}
