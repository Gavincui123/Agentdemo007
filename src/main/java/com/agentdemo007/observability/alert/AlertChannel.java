package com.agentdemo007.observability.alert;

/**
 * 告警通道 seam（Phase 15·T71，dev-plan "对接告警通道"）。
 *
 * <p>将已触发 {@link Alert} 发往外部通道。dev {@link #NO_OP} 丢弃（不阻塞评估器）；
 * prod 覆盖为 Webhook/邮件/PagerDuty/IM。@FunctionalInterface 便于单测注入录制/抛异常实现。
 *
 * <p>与 {@code ConfigWriter}/{@code RegistrationWriter}/{@code EvalAuthenticator} 同为引擎无关 seam：
 * 通道实现可插拔，dev 不阻塞联调，prod 用真实通道覆盖。
 */
@FunctionalInterface
public interface AlertChannel {

    void send(Alert alert);

    /** dev 空实现：告警被丢弃（不阻塞评估器）。 */
    AlertChannel NO_OP = alert -> { };
}
