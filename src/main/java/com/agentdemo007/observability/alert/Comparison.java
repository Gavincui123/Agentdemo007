package com.agentdemo007.observability.alert;

/**
 * 告警阈值比较算子（Phase 15·T71，配置友好——可序列化为 JSON：GT/LT）。
 *
 * <p>{@link #GT}：值大于阈值触发（错误率/延迟/队列深度超限）；
 * {@link #LT}：值小于阈值触发（成功率/水位低于下限）。
 */
public enum Comparison {

    GT {
        @Override
        public boolean fires(double value, double threshold) {
            return value > threshold;
        }
    },
    LT {
        @Override
        public boolean fires(double value, double threshold) {
            return value < threshold;
        }
    };

    public abstract boolean fires(double value, double threshold);
}
