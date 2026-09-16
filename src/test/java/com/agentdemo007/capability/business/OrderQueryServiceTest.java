package com.agentdemo007.capability.business;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单查询服务测试（Slice 1·[[business-tools-workflow-dag]]）。
 *
 * <p>mock 数据须已覆盖 DAG validate 节点（Slice 3）的全部校验分支：
 * 本人+7 天内（ORD-001）/ 本人+超 7 天（ORD-002）/ 非本人（ORD-003）/ 不存在（ORD-999）。
 * 7 天窗口判定在 Slice 3 校验规则（注入时钟），本测试只钉 mock 数据字段。
 */
class OrderQueryServiceTest {

    private final OrderQueryService service = new OrderQueryService();

    @Test
    void findByOrderId_ownedWithinWindow_returnsRecord() {
        // ORD-001：当前账户 10086，3 天前下单（7 天内，可退货）
        Optional<OrderRecord> found = service.findByOrderId("ORD-001");

        assertThat(found).isPresent();
        OrderRecord order = found.get();
        assertThat(order.orderId()).isEqualTo("ORD-001");
        assertThat(order.userId()).isEqualTo("10086");
        assertThat(order.orderTime()).isEqualTo(Instant.parse("2026-09-09T10:00:00Z"));
        assertThat(order.status()).isEqualTo("已签收");
        assertThat(order.items()).contains("无线耳机");
        assertThat(order.amount()).isEqualByComparingTo(new BigDecimal("299.00"));
    }

    @Test
    void findByOrderId_ownedBeyondWindow_returnsRecord() {
        // ORD-002：当前账户 10086，11 天前下单（超 7 天无理由，BEYOND_7_DAY）
        Optional<OrderRecord> found = service.findByOrderId("ORD-002");

        assertThat(found).isPresent();
        OrderRecord order = found.get();
        assertThat(order.userId()).isEqualTo("10086");
        assertThat(order.orderTime()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
    }

    @Test
    void findByOrderId_notOwned_returnsRecord() {
        // ORD-003：属于 10010（非当前账户 10086，ORDER_NOT_OWNED）
        Optional<OrderRecord> found = service.findByOrderId("ORD-003");

        assertThat(found).isPresent();
        OrderRecord order = found.get();
        assertThat(order.userId()).isEqualTo("10010");
        assertThat(order.userId()).isNotEqualTo("10086");
    }

    @Test
    void findByOrderId_notFound_empty() {
        // ORD-999：不存在（ORDER_NOT_FOUND）
        assertThat(service.findByOrderId("ORD-999")).isEmpty();
    }

    @Test
    void findByOrderId_null_empty() {
        assertThat(service.findByOrderId(null)).isEmpty();
    }
}
