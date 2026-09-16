package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.business.OrderRecord;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 退款校验规则（[[business-tools-workflow-dag]] §2.3·退款窗口）。
 *
 * <p>校验顺序：存在→归属→退款窗口，任一失败即返对应 {@link Reason}（短路）。
 * 退款窗口默认 30 天（退款办理时限长于 7 天无理由退货）；超窗 → {@link Reason#REFUND_WINDOW_EXPIRED}。
 * 窗口可注入（测试用 2 天窗口触发超窗分支）。
 */
public class RefundValidationRule implements AfterSaleValidationRule {

    private static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private final Clock clock;
    private final Duration window;

    public RefundValidationRule(Clock clock) {
        this(clock, DEFAULT_WINDOW);
    }

    public RefundValidationRule(Clock clock, Duration window) {
        this.clock = clock;
        this.window = window;
    }

    @Override
    public Optional<Reason> validate(OrderRecord order, String currentUserId) {
        if (order == null) {
            return Optional.of(Reason.ORDER_NOT_FOUND);
        }
        if (currentUserId != null && !currentUserId.equals(order.userId())) {
            return Optional.of(Reason.ORDER_NOT_OWNED);
        }
        if (order.orderTime().isBefore(Instant.now(clock).minus(window))) {
            return Optional.of(Reason.REFUND_WINDOW_EXPIRED);
        }
        return Optional.empty();
    }
}
