package com.agentdemo007.capability.business;

import com.agentdemo007.persistence.entity.BizOrderEntity;
import com.agentdemo007.persistence.repository.BizOrderRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BizOrderSeedRunner} 单测（2026-09-20 修复：业务门对账 biz_order 表空 → 管理台误拒"订单不存在"）。
 *
 * <p>验四件事：<b>fill-if-absent</b>（空表全种、已有行绝不覆盖——业务表是对账锚点，真实数据/已流转
 * 状态不可被种子回写）；<b>降级</b>（仓库缺席/落库失败不阻塞启动）；<b>同源对齐钉</b>（种子行必须与
 * {@link OrderQueryService} mock 的 orderId/userId/amount 一致——工作流校验与业务门对账两侧数据源
 * 一致性正是本次缺陷的根因，此断言防其复发）。
 */
class BizOrderSeedRunnerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<BizOrderRepository> provider = mock(ObjectProvider.class);
    private final BizOrderRepository repo = mock(BizOrderRepository.class);
    private final BizOrderSeedRunner runner = new BizOrderSeedRunner(provider, CLOCK);

    // ---- fill-if-absent ----

    @Test
    void seedsAllDemoOrders_whenTableEmpty() {
        when(provider.getIfAvailable()).thenReturn(repo);
        when(repo.existsById(any())).thenReturn(false);

        runner.run(null);

        ArgumentCaptor<BizOrderEntity> saved = ArgumentCaptor.forClass(BizOrderEntity.class);
        verify(repo, times(3)).save(saved.capture());
        List<BizOrderEntity> rows = saved.getAllValues();
        assertThat(rows).extracting(BizOrderEntity::getOrderId)
                .containsExactlyInAnyOrder("ORD-001", "ORD-002", "ORD-003");
        for (BizOrderEntity row : rows) {
            // 已签收订单映射：PAID（退款对账前提）+ DELIVERED（退货对账前提）+ 无在途退款
            assertThat(row.getPaymentStatus()).isEqualTo("PAID");
            assertThat(row.getLogisticsStatus()).isEqualTo("DELIVERED");
            assertThat(row.getRefundStatus()).isEqualTo("NONE");
            assertThat(row.getUpdatedAt()).isEqualTo(Instant.parse("2026-09-20T12:00:00Z"));
        }
        assertThat(rows).extracting(BizOrderEntity::getUserId)
                .containsExactlyInAnyOrder("10086", "10086", "10010");
        assertThat(rows).extracting(BizOrderEntity::getAmount)
                .containsExactlyInAnyOrder(new java.math.BigDecimal("299.00"),
                        new java.math.BigDecimal("199.00"), new java.math.BigDecimal("39.00"));
    }

    @Test
    void fillsOnlyAbsent_existingRowsUntouched() {
        when(provider.getIfAvailable()).thenReturn(repo);
        when(repo.existsById("ORD-001")).thenReturn(true); // 已有（如真实数据/已流转 REFUNDING）
        when(repo.existsById("ORD-002")).thenReturn(false);
        when(repo.existsById("ORD-003")).thenReturn(false);

        runner.run(null);

        ArgumentCaptor<BizOrderEntity> saved = ArgumentCaptor.forClass(BizOrderEntity.class);
        verify(repo, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(BizOrderEntity::getOrderId)
                .containsExactlyInAnyOrder("ORD-002", "ORD-003"); // ORD-001 未被覆盖
    }

    // ---- 降级 ----

    @Test
    void skipsGracefully_whenRepositoryAbsent() {
        when(provider.getIfAvailable()).thenReturn(null); // 无 JPA 受限切片

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        verify(repo, never()).save(any());
    }

    @Test
    void seedFailure_nonBlocking() {
        when(provider.getIfAvailable()).thenReturn(repo);
        when(repo.existsById(any())).thenReturn(false);
        org.mockito.Mockito.when(repo.save(any(BizOrderEntity.class)))
                .thenThrow(new IllegalStateException("DB 不可达"));

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException(); // ②降级：失败不阻塞启动
    }

    // ---- 同源对齐钉（本缺陷根因的防复发断言）----

    @Test
    void seedRows_alignedWithOrderQueryServiceMock() {
        when(provider.getIfAvailable()).thenReturn(repo);
        when(repo.existsById(any())).thenReturn(false);

        runner.run(null);

        ArgumentCaptor<BizOrderEntity> saved = ArgumentCaptor.forClass(BizOrderEntity.class);
        verify(repo, times(3)).save(saved.capture());
        OrderQueryService mock = new OrderQueryService();
        for (BizOrderEntity row : saved.getAllValues()) {
            // 工作流校验（mock）与业务门对账（biz_order）必须看到同一笔订单：
            // 归属一致、金额一致、状态语义一致（已签收→DELIVERED+PAID）
            Optional<OrderRecord> demo = mock.findByOrderId(row.getOrderId());
            assertThat(demo).as("种子订单 %s 必须在 OrderQueryService mock 中存在", row.getOrderId()).isPresent();
            assertThat(row.getUserId()).isEqualTo(demo.orElseThrow().userId());
            assertThat(row.getAmount()).isEqualByComparingTo(demo.orElseThrow().amount());
            assertThat(demo.orElseThrow().status()).isEqualTo("已签收");
            assertThat(row.getLogisticsStatus()).isEqualTo("DELIVERED");
        }
    }
}
