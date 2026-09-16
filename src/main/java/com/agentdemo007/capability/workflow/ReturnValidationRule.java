package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.business.OrderRecord;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 退货校验规则（[[business-tools-workflow-dag]] §2.3·7 天无理由退货窗口）。
 *
 * <p>校验顺序：存在→归属→7 天窗口，任一失败即返对应 {@link Reason}（短路，不继续）。
 * 7 天窗口 = {@code orderTime >= now - 7d}；超窗 → {@link Reason#BEYOND_7_DAY}。
 * 窗口可注入（测试用短窗口触发超窗分支）。
 */
public class ReturnValidationRule implements AfterSaleValidationRule {

    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    private final Clock clock;
    private final Duration window;

    public ReturnValidationRule(Clock clock) {
        this(clock, DEFAULT_WINDOW);
    }

    public ReturnValidationRule(Clock clock, Duration window) {
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
            return Optional.of(Reason.BEYOND_7_DAY);
        }
        return Optional.empty();
    }
}
