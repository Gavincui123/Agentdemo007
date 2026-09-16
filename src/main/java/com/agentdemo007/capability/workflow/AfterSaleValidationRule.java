package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.business.OrderRecord;

import java.util.Optional;

/**
 * 售后校验规则 seam（[[business-tools-workflow-dag]] §2.3·validate 节点委托此，纯逻辑）。
 *
 * <p>校验订单归属/存在/时间窗口，返失败 {@link Reason}（empty=pass → 进 submit_approval）。
 * {@link ReturnValidationRule}（7 天无理由）/ {@link RefundValidationRule}（退款窗口）两 impl，
 * 注入 {@link java.time.Clock} 保测试确定性（[[code-review-hardening-pass]] 同款固定时钟）。
 */
public interface AfterSaleValidationRule {

    /**
     * 校验订单；返失败 Reason（empty=pass → 进 submit_approval）。
     *
     * @param order         query_order 节点召回的订单（可为 null → {@link Reason#ORDER_NOT_FOUND}）
     * @param currentUserId 当前账户（与 order.userId 比对归属）
     */
    Optional<Reason> validate(OrderRecord order, String currentUserId);
}
