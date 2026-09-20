package com.agentdemo007.capability.hitl;

import com.agentdemo007.persistence.entity.BizOrderEntity;
import com.agentdemo007.persistence.repository.BizOrderRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HITL 业务前置校验门测试（2026-09-18 用户裁决：工单状态收尾不自证，结合业务）。
 *
 * <p>覆盖：退款对账支付状态（PAID 放行；UNPAID/REFUNDING/REFUNDED 拒绝）、退货对账物流状态
 * （DELIVERED 放行；NOT_SHIPPED/SHIPPED 拒绝）、订单不存在/查询异常 fail-closed、
 * 无订单库降级跳过、无业务锚点（null 键/q: 摘要锚点）与非退款/退货动作跳过。
 */
class HitlBusinessGateTest {

    private static final Instant T = Instant.parse("2026-09-17T12:00:00Z");

    private static BizOrderEntity order(String payment, String logistics, String refund) {
        return new BizOrderEntity("ORD-001", "U1001", payment, logistics, refund,
                new BigDecimal("199.00"), T);
    }

    private static HumanTicket ticket(String key) {
        return new HumanTicket("t-1", "sess-1", "q", "r", HumanTicket.Status.PENDING,
                T, null, key);
    }

    @Test
    void refund_paidOrder_allowed() {
        BizOrderRepository repo = mock(BizOrderRepository.class);
        when(repo.findById("ORD-001")).thenReturn(Optional.of(order("PAID", "DELIVERED", "NONE")));

        HitlBusinessGate.Decision d = new HitlBusinessGate(repo).check(ticket("hitl:REFUND:ORD-001"));

        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).contains("已支付");
    }

    @Test
    void refund_unpaidOrder_blocked() {
        BizOrderRepository repo = mock(BizOrderRepository.class);
        when(repo.findById("ORD-001")).thenReturn(Optional.of(order("UNPAID", "NOT_SHIPPED", "NONE")));

        HitlBusinessGate.Decision d = new HitlBusinessGate(repo).check(ticket("hitl:REFUND:ORD-001"));

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("未支付");
    }

    @Test
    void refund_refundingOrder_blocked() {
        BizOrderRepository repo = mock(BizOrderRepository.class);
        when(repo.findById("ORD-001")).thenReturn(Optional.of(order("REFUNDING", "DELIVERED", "APPLYING")));

        assertThat(new HitlBusinessGate(repo).check(ticket("hitl:REFUND:ORD-001")).allowed()).isFalse();
    }

    @Test
    void refund_alreadyRefunded_blocked() {
        BizOrderRepository repo = mock(BizOrderRepository.class);
        when(repo.findById("ORD-001")).thenReturn(Optional.of(order("REFUNDED", "DELIVERED", "REFUNDED")));

        HitlBusinessGate.Decision d = new HitlBusinessGate(repo).check(ticket("hitl:REFUND:ORD-001"));

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("已退款");
    }

    @Test
    void return_deliveredOrder_allowed() {
        BizOrderRepository repo = mock(BizOrderRepository.class);
        when(repo.findById("ORD-001")).thenReturn(Optional.of(order("PAID", "DELIVERED", "NONE")));

        HitlBusinessGate.Decision d = new HitlBusinessGate(repo).check(ticket("hitl:RETURN:ORD-001"));

        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).contains("退货");
    }

    @Test
    void return_notShipped_blocked() {
        BizOrderRepository repo = mock(BizOrderRepository.class);
        when(repo.findById("ORD-001")).thenReturn(Optional.of(order("PAID", "NOT_SHIPPED", "NONE")));

        assertThat(new HitlBusinessGate(repo).check(ticket("hitl:RETURN:ORD-001")).allowed()).isFalse();
    }

    @Test
    void return_inTransit_blocked() {
        BizOrderRepository repo = mock(BizOrderRepository.class);
        when(repo.findById("ORD-001")).thenReturn(Optional.of(order("PAID", "SHIPPED", "NONE")));

        HitlBusinessGate.Decision d = new HitlBusinessGate(repo).check(ticket("hitl:RETURN:ORD-001"));

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("在途");
    }

    @Test
    void orderMissing_failClosed() {
        BizOrderRepository repo = mock(BizOrderRepository.class);
        when(repo.findById("ORD-404")).thenReturn(Optional.empty());

        HitlBusinessGate.Decision d = new HitlBusinessGate(repo).check(ticket("hitl:REFUND:ORD-404"));

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("订单不存在");
    }

    @Test
    void queryFailure_failClosed() {
        BizOrderRepository repo = mock(BizOrderRepository.class);
        when(repo.findById("ORD-001")).thenThrow(new RuntimeException("db down"));

        HitlBusinessGate.Decision d = new HitlBusinessGate(repo).check(ticket("hitl:REFUND:ORD-001"));

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("查询失败");
    }

    @Test
    void unknownPaymentStatus_failClosed() {
        BizOrderRepository repo = mock(BizOrderRepository.class);
        when(repo.findById("ORD-001")).thenReturn(Optional.of(order("FROZEN", "DELIVERED", "NONE")));

        HitlBusinessGate.Decision d = new HitlBusinessGate(repo).check(ticket("hitl:REFUND:ORD-001"));

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("FROZEN");
    }

    @Test
    void noOrderRepo_degradesToSkip_allowed() {
        // 受限单测切片/未建表环境：显式跳过业务校验（§5.12 不阻塞审批链路）
        assertThat(new HitlBusinessGate(null).check(ticket("hitl:REFUND:ORD-001")).allowed()).isTrue();
    }

    @Test
    void ticketWithoutBusinessKey_skipped() {
        BizOrderRepository repo = mock(BizOrderRepository.class);
        assertThat(new HitlBusinessGate(repo).check(ticket(null)).allowed()).isTrue();
    }

    @Test
    void digestAnchorTicket_noOrderEntity_skipped() {
        // q: 摘要锚点工单（无订单实体）→ 无业务对账对象，跳过
        BizOrderRepository repo = mock(BizOrderRepository.class);
        assertThat(new HitlBusinessGate(repo).check(ticket("hitl:REFUND:Q:abc123")).allowed()).isTrue();
    }

    @Test
    void nonAfterSaleAction_skipped() {
        BizOrderRepository repo = mock(BizOrderRepository.class);
        assertThat(new HitlBusinessGate(repo).check(ticket("hitl:TRANSFER_TO_HUMAN:ORD-001")).allowed()).isTrue();
    }
}
