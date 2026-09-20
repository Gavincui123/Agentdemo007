package com.agentdemo007.capability.business;

import com.agentdemo007.persistence.entity.BizOrderEntity;
import com.agentdemo007.persistence.repository.BizOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 业务订单种子装载器（2026-09-20 修复：管理台业务门对账 {@code biz_order} 表空 → 「订单不存在」误拒）。
 *
 * <p><b>数据源分裂根因</b>：工作流图校验（订单存在/归属）走 {@link OrderQueryService} 内存 mock
 * （ORD-001/002 归 10086、ORD-003 归 10010），而 {@code HitlBusinessGate} 对账走 {@code biz_order}
 * 表——表只有 DDL 无种子，运行时恒空 → 管理台 confirm 必然 fail-closed「订单不存在」。本 Runner
 * 启动时把<b>与 mock 同源对齐</b>的演示订单写入业务表，使「Agent 所见」与「业务门所对账」一致
 * （对齐 2026-09-18 用户裁决"工具调用对应数据必须准确"；对齐断言由 {@code BizOrderSeedRunnerTest}
 * 钉死，mock 改动不同步会测试失败）。
 *
 * <p><b>fill-if-absent 语义</b>：只补空缺、<b>不覆盖已有行</b>——业务表是对账锚点，若真实数据/
 * 已流转状态（如 REFUNDING）存在，一律以表中现值为准（种子绝不回写业务状态）。幂等可重跑。
 * {@code app.biz-order.seed.enabled=false} 可关闭（真订单系统接入后应关闭）；仓库缺席（无 JPA
 * 受限切片）显式跳过；失败仅告警不阻塞启动（②每步降级，业务门本就 fail-closed 兜底）。
 */
@Component
@ConditionalOnProperty(name = "app.biz-order.seed.enabled", matchIfMissing = true)
public class BizOrderSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BizOrderSeedRunner.class);

    /**
     * 演示订单种子（<b>必须与 {@link OrderQueryService} mock 同源</b>：orderId/userId/amount 一致，
     * 中文状态「已签收」映射 DELIVERED、已签收订单按已支付映射 PAID——对齐断言钉死）。
     */
    private static final List<BizOrderEntity> DEMO_ORDERS = List.of(
            new BizOrderEntity("ORD-001", "10086", "PAID", "DELIVERED", "NONE",
                    new BigDecimal("299.00"), Instant.EPOCH),
            new BizOrderEntity("ORD-002", "10086", "PAID", "DELIVERED", "NONE",
                    new BigDecimal("199.00"), Instant.EPOCH),
            new BizOrderEntity("ORD-003", "10010", "PAID", "DELIVERED", "NONE",
                    new BigDecimal("39.00"), Instant.EPOCH));

    private final ObjectProvider<BizOrderRepository> repository;
    private final Clock clock;

    public BizOrderSeedRunner(ObjectProvider<BizOrderRepository> repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        BizOrderRepository repo = repository.getIfAvailable();
        if (repo == null) {
            log.info("业务订单种子跳过（无 JPA 仓库，受限切片）");
            return;
        }
        try {
            int seeded = 0;
            for (BizOrderEntity demo : DEMO_ORDERS) {
                if (repo.existsById(demo.getOrderId())) {
                    continue; // fill-if-absent：已有行（真实数据/已流转状态）绝不覆盖
                }
                repo.save(new BizOrderEntity(demo.getOrderId(), demo.getUserId(),
                        demo.getPaymentStatus(), demo.getLogisticsStatus(), demo.getRefundStatus(),
                        demo.getAmount(), clock.instant()));
                seeded++;
            }
            log.info("业务订单种子完成（fill-if-absent）：biz_order 新补 {} 行，共 {} 笔演示订单"
                            + "（与 OrderQueryService mock 同源，管理台业务门对账锚点）",
                    seeded, DEMO_ORDERS.size());
        } catch (Exception e) {
            log.warn("业务订单种子失败，跳过（不阻塞启动；业务门对账仍 fail-closed）：{}", e.getMessage());
        }
    }
}
