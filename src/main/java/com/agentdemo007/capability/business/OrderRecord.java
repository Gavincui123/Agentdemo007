package com.agentdemo007.capability.business;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 订单记录（外部系统高置信数据 carrier·[[business-tools-workflow-dag]] §2.1）。
 *
 * <p>typed mock 数据：@Tool 包装（RunTime_* 通道）与 DAG query_order 节点共用同一份。
 * orderTime 供 7 天无理由/退款窗口校验（Slice 3 ReturnValidationRule/RefundValidationRule 注入时钟判定）。
 */
public record OrderRecord(
        String orderId,
        String userId,
        Instant orderTime,
        String status,
        List<String> items,
        BigDecimal amount) {
}
