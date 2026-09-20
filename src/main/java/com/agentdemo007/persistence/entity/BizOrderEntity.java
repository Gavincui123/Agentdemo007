package com.agentdemo007.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 业务订单实体（L2 HITL 业务前置校验·2026-09-18 用户裁决）。
 *
 * <p>订单域最小强类型契约：{@code HitlBusinessGate} 在工单审批放行前对账订单真实状态——
 * 退款动作查 {@code payment_status}（未支付/已退款/退款中均不放行），退货动作查
 * {@code logistics_status}（未发货/在途不放行，已签收放行）。工单状态收尾不再自证，
 * 以业务数据为准（不伪造业务结果）。
 *
 * <p>自然主键 {@code order_id}（业务单号，非自增）；状态列为 {@code String}（枚举外置，
 * mock 数据/真系统接入时无需迁移即可扩展状态字面量）。建表：见 {@code docs/sql/hitl_l2_init.sql}
 * （用户自建）或 JPA {@code ddl-auto=update} 自动建表；{@code src/main/resources/schema.sql}
 * 含幂等 DDL（JPA_DDL=none 的 prod 口径）。
 */
@Entity
@Table(name = "biz_order")
public class BizOrderEntity {

    @Id
    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "user_id", length = 64)
    private String userId;

    /** 支付状态（UNPAID / PAID / REFUNDING / REFUNDED）。 */
    @Column(name = "payment_status", length = 32)
    private String paymentStatus;

    /** 物流状态（NOT_SHIPPED / SHIPPED / DELIVERED / RETURN_IN_TRANSIT / RETURN_RECEIVED）。 */
    @Column(name = "logistics_status", length = 32)
    private String logisticsStatus;

    /** 退款单状态（NONE / APPLYING / APPROVED / REFUNDED / REJECTED）。 */
    @Column(name = "refund_status", length = 32)
    private String refundStatus;

    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 代理要求的无参构造。 */
    protected BizOrderEntity() {
    }

    public BizOrderEntity(String orderId, String userId, String paymentStatus,
                          String logisticsStatus, String refundStatus,
                          BigDecimal amount, Instant updatedAt) {
        this.orderId = orderId;
        this.userId = userId;
        this.paymentStatus = paymentStatus;
        this.logisticsStatus = logisticsStatus;
        this.refundStatus = refundStatus;
        this.amount = amount;
        this.updatedAt = updatedAt;
    }

    // ---- 读写访问器 ----

    public String getOrderId() {
        return orderId;
    }

    public String getUserId() {
        return userId;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public String getLogisticsStatus() {
        return logisticsStatus;
    }

    public String getRefundStatus() {
        return refundStatus;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
