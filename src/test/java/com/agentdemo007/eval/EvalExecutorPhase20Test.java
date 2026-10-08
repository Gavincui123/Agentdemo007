package com.agentdemo007.eval;

import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.capability.plan.RoutePlanCandidate;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineExecutor;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.session.model.QueryEnrichment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测执行器 Phase 20 字段对照测评（T92）。
 *
 * <p>扩展 {@link EvalExpected}/{@link ActualOutcome}/{@link EvalExecutor#compare} 支持 Phase 20
 * 可派生字段：{@code queryEnrichmentContains}（约束改写补全精确词）、{@code ragFragmentsContain}
 * （Hybrid 命中/时效标注文本）、{@code hasFragments}（片段非空）——使 ③环节测评 对 Phase 20 行为
 * 真正可断言（非仅文档字段被忽略）。fake 执行器显式设 queryEnrichment/ragFragments，隔离真实流水线。
 */
class EvalExecutorPhase20Test {

    /** fake 执行器：设 queryEnrichment + ragFragments，返回 ok（模拟 Phase 20 产出）。 */
    private PipelineExecutor fakeExecutorWithPhase20Produce() {
        return new PipelineExecutor() {
            @Override
            public PipelineResult run(PipelineContext context) {
                context.setQueryEnrichment(new QueryEnrichment(List.of("ORD123456"), "Q3"));
                context.setRagFragments(List.of("订单ORD123456详情"));
                return PipelineResult.ok("ok");
            }
        };
    }

    @Test
    void compare_phase20Fields_passWhenAllMatch() {
        EvalFile file = new EvalFile("test", "phase20", List.of(
                new EvalCase("ok1", "查订单 ORD123456",
                        new EvalExpected(null, null, null, null, null, "PROCEED",
                                null, null, null, null,
                                null,
                                "ORD123456", "ORD123456", true),
                        "精确词补全 + Hybrid 命中 + 片段非空")));
        EvalExecutor executor = new EvalExecutor(fakeExecutorWithPhase20Produce());

        EvalReport report = executor.run(List.of(file));

        assertThat(report.totalPassed()).isEqualTo(1);
    }

    @Test
    void compare_queryEnrichmentMismatch_fails() {
        EvalFile file = new EvalFile("test", "phase20", List.of(
                new EvalCase("bad1", "x",
                        new EvalExpected(null, null, null, null, null, null,
                                null, null, null, null,
                                null,
                                "NOTFOUND", null, null),
                        "补全词不匹配")));
        EvalExecutor executor = new EvalExecutor(fakeExecutorWithPhase20Produce());

        EvalReport report = executor.run(List.of(file));

        assertThat(report.totalPassed()).isZero();
        assertThat(report.stages().get(0).cases().get(0).mismatches())
                .anyMatch(m -> m.contains("queryEnrichmentContains"));
    }

    @Test
    void compare_ragFragmentsContainMismatch_fails() {
        EvalFile file = new EvalFile("test", "phase20", List.of(
                new EvalCase("bad2", "x",
                        new EvalExpected(null, null, null, null, null, null,
                                null, null, null, null,
                                null,
                                null, "不存在的文本", null),
                        "片段文本不匹配")));
        EvalExecutor executor = new EvalExecutor(fakeExecutorWithPhase20Produce());

        EvalReport report = executor.run(List.of(file));

        assertThat(report.totalPassed()).isZero();
        assertThat(report.stages().get(0).cases().get(0).mismatches())
                .anyMatch(m -> m.contains("ragFragmentsContain"));
    }

    @Test
    void compare_hasFragmentsMismatch_fails() {
        EvalFile file = new EvalFile("test", "phase20", List.of(
                new EvalCase("bad3", "x",
                        new EvalExpected(null, null, null, null, null, null,
                                null, null, null, null,
                                null,
                                null, null, false),
                        "期望无片段但实际有")));
        EvalExecutor executor = new EvalExecutor(fakeExecutorWithPhase20Produce());

        EvalReport report = executor.run(List.of(file));

        assertThat(report.totalPassed()).isZero();
        assertThat(report.stages().get(0).cases().get(0).mismatches())
                .anyMatch(m -> m.contains("hasFragments"));
    }

    // ---- 2026-09-28：routeIntent（业务意图）对照（线上「退款流程是什么」误判 refund_request 事故回归锚）----

    /** fake 执行器：设 routePlan=faq_query（模拟咨询问法的正确业务路由）。 */
    private PipelineExecutor fakeExecutorWithFaqRoute() {
        return new PipelineExecutor() {
            @Override
            public PipelineResult run(PipelineContext context) {
                context.setRoutePlan(new RoutePlan(new RoutePlanCandidate("faq_query", true, false,
                        List.of(), List.of("faq"), RoutePlanCandidate.RiskLevel.LOW, false,
                        RoutePlanCandidate.FallbackPolicy.KNOWLEDGE_ONLY),
                        RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, List.of()));
                return PipelineResult.ok("ok");
            }
        };
    }

    @Test
    void compare_routeIntent_passWhenMatch() {
        EvalFile file = new EvalFile("test", "route-intent", List.of(
                new EvalCase("ri1", "退款流程是什么",
                        new EvalExpected(null, null, null, null, null, null,
                                null, null, null, null,
                                "faq_query", null, null, null),
                        "业务意图匹配")));
        EvalExecutor executor = new EvalExecutor(fakeExecutorWithFaqRoute());

        EvalReport report = executor.run(List.of(file));

        assertThat(report.totalPassed()).isEqualTo(1);
    }

    @Test
    void compare_routeIntentMismatch_fails() {
        EvalFile file = new EvalFile("test", "route-intent", List.of(
                new EvalCase("ri2", "退款流程是什么",
                        new EvalExpected(null, null, null, null, null, null,
                                null, null, null, null,
                                "refund_request", null, null, null),
                        "期望咨询意图但路由给了 refund_request（线上事故形态）")));
        EvalExecutor executor = new EvalExecutor(fakeExecutorWithFaqRoute());

        EvalReport report = executor.run(List.of(file));

        assertThat(report.totalPassed()).isZero();
        assertThat(report.stages().get(0).cases().get(0).mismatches())
                .anyMatch(m -> m.contains("routeIntent"));
    }
}
