package com.agentdemo007.eval;

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
                                null, null, false),
                        "期望无片段但实际有")));
        EvalExecutor executor = new EvalExecutor(fakeExecutorWithPhase20Produce());

        EvalReport report = executor.run(List.of(file));

        assertThat(report.totalPassed()).isZero();
        assertThat(report.stages().get(0).cases().get(0).mismatches())
                .anyMatch(m -> m.contains("hasFragments"));
    }
}
