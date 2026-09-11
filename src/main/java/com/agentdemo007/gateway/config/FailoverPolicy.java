package com.agentdemo007.gateway.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型容灾策略（主模型超时/异常时的故障转移行为）。
 *
 * <p>由 {@code FailoverExecutor} 消费：按策略依次尝试备选模型并限制重试次数；
 * 转移事件写入审计 + 指标埋点。
 */
public final class FailoverPolicy {

    public enum FailoverStrategy { ORDERED, WEIGHTED, ROUND_ROBIN }

    private final String id;
    private final int maxRetries;
    private final List<String> fallbackModelIds;
    private final Duration timeout;
    private final FailoverStrategy strategy;

    private FailoverPolicy(String id, int maxRetries, List<String> fallbackModelIds,
                           Duration timeout, FailoverStrategy strategy) {
        this.id = id;
        this.maxRetries = maxRetries;
        this.fallbackModelIds = fallbackModelIds;
        this.timeout = timeout;
        this.strategy = strategy;
    }

    public String id() { return id; }
    public int maxRetries() { return maxRetries; }
    public List<String> fallbackModelIds() { return fallbackModelIds; }
    public Duration timeout() { return timeout; }
    public FailoverStrategy strategy() { return strategy; }

    public static Builder builder(String id) { return new Builder(id); }

    public static final class Builder {
        private String id;
        private int maxRetries = 0;
        private List<String> fallbackModelIds = new ArrayList<>();
        private Duration timeout = Duration.ofSeconds(30);
        private FailoverStrategy strategy = FailoverStrategy.ORDERED;

        public Builder(String id) { this.id = id; }

        public Builder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
        public Builder fallbackModelIds(List<String> fallbackModelIds) { this.fallbackModelIds = new ArrayList<>(fallbackModelIds); return this; }
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }
        public Builder strategy(FailoverStrategy strategy) { this.strategy = strategy; return this; }
        public FailoverPolicy build() {
            return new FailoverPolicy(id, maxRetries, fallbackModelIds, timeout, strategy);
        }
    }
}
