package com.agentdemo007.session.rewrite;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.session.model.StandardQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 改写质量校验"未新增业务结论"守卫测评（Phase 20·T88d）。
 *
 * <p>约束改写不得替用户下业务结论：若改写凭空断言业务状态（已发货/已退款/已到账/已完成 等）
 * 而**用户原问题未提及**该状态 → 视为失真，回退原问题（②每步降级，不阻塞）。
 * 用户原话提及该状态（询问/引用）时改写保留之不算新增结论 → 通过。
 */
class RewriteQualityCheckerConclusionTest {

    private final RewriteQualityChecker checker = new RewriteQualityChecker();

    @Test
    void rewriteFabricatesBusinessStatus_fallsBackToRawInput() {
        // 原问题只问查订单，改写凭空断言"已发货"→ 替用户下结论 → 回退
        PipelineContext ctx = new PipelineContext("s1", "查订单 ORD123456");
        ctx.setStandardQuery(StandardQuery.of("订单 ORD123456 已发货"));

        StepOutcome outcome = checker.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("查订单 ORD123456"));
    }

    @Test
    void rewriteFabricatesRefundStatus_fallsBackToRawInput() {
        PipelineContext ctx = new PipelineContext("s1", "退款进度 ORD123456");
        ctx.setStandardQuery(StandardQuery.of("订单 ORD123456 已退款到账"));

        StepOutcome outcome = checker.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("退款进度 ORD123456"));
    }

    @Test
    void rewritePreservingUserMentionedStatus_passes() {
        // 用户原话提到"已发货"（询问是否），改写保留之 → 非新增结论 → 通过
        PipelineContext ctx = new PipelineContext("s1", "订单 ORD123456 已发货了吗");
        ctx.setStandardQuery(StandardQuery.of("订单 ORD123456 是否已发货"));

        StepOutcome outcome = checker.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("订单 ORD123456 是否已发货"));
    }

    @Test
    void rewriteWithoutStatusMarkers_passes() {
        PipelineContext ctx = new PipelineContext("s1", "那它呢");
        ctx.setStandardQuery(StandardQuery.of("Q3 销售额怎么样"));

        StepOutcome outcome = checker.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("Q3 销售额怎么样"));
    }
}
