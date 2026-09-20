package com.agentdemo007.eval;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineExecutor;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.gateway.config.RouteRule;
import com.agentdemo007.intent.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/**
 * 评测执行器测试（Phase 15·T69 环节测评）。
 *
 * <p>{@link EvalExecutor} 加载 eval/*.json 黄金数据 → 经 {@link PipelineExecutor} 跑每例 →
 * 从 {@link PipelineResult}+{@link PipelineContext} 派生实际产出（scenario/degraded/intent/route/
 * selectedModel/outcome/blocked/zeroLlm/shortCircuit）→ 对照 {@link EvalExpected} 非空字段判定 →
 * 按 stage 聚合报告。单测用受控 fake 执行器隔离真实流水线，断言通过/失败计数 + per-case 结果 +
 * scenario→outcome/zeroLlm 派生。
 */
class EvalExecutorTest {

    /** fake 执行器：按 input 关键词设 context 字段 + 返回受控 PipelineResult。 */
    private PipelineExecutor fakeExecutor() {
        return new PipelineExecutor() {
            @Override
            public PipelineResult run(PipelineContext context) {
                String input = context.rawInput();
                if (input != null && input.contains("注入")) {
                    context.setIntent(Intent.INJECTION);
                    return PipelineResult.shortCircuit(
                            DegradationScenario.INJECTION.phrase(), DegradationScenario.INJECTION);
                }
                context.setIntent(Intent.REASONING);
                context.setRouteType(RouteRule.RouteType.REASONING);
                context.setSelectedModelId("m-reasoning");
                return PipelineResult.ok("分析结果");
            }
        };
    }

    private EvalCase passCase(String id, String input) {
        return new EvalCase(id, input,
                new EvalExpected("NONE", "REASONING", "REASONING", "m-reasoning",
                        false, "PROCEED", null, null, null, null),
                "正常推理");
    }

    private EvalCase injectionCase(String id, String input) {
        return new EvalCase(id, input,
                new EvalExpected("INJECTION", "INJECTION", null, null,
                        true, "SHORT_CIRCUIT", true, true, true, null),
                "注入短路零LLM");
    }

    private EvalCase mismatchCase(String id, String input) {
        // expected intent=CHIT_CHAT，但 fake 会给 REASONING → mismatch
        return new EvalCase(id, input,
                new EvalExpected(null, "CHIT_CHAT", null, null, null, null, null, null, null, null),
                "意图不匹配");
    }

    @Test
    void run_comparesExpectedAndReports_passFailCounts() {
        EvalFile file = new EvalFile("intent", "测试", List.of(
                passCase("p1", "分析 Q3"),
                injectionCase("p2", "注入攻击"),
                mismatchCase("f1", "分析 Q3")));
        EvalExecutor executor = new EvalExecutor(fakeExecutor());

        EvalReport report = executor.run(List.of(file));

        assertThat(report.totalCases()).isEqualTo(3);
        assertThat(report.totalPassed()).isEqualTo(2);
        assertThat(report.stages()).hasSize(1);
        StageReport stage = report.stages().get(0);
        assertThat(stage.stage()).isEqualTo("intent");
        assertThat(stage.passed()).isEqualTo(2);
        assertThat(stage.total()).isEqualTo(3);
        assertThat(stage.passRate()).isCloseTo(2.0 / 3.0, within(0.001));
        assertThat(stage.cases()).extracting(CaseResult::caseId, CaseResult::passed)
                .containsExactlyInAnyOrder(
                        tuple("p1", true), tuple("p2", true), tuple("f1", false));
        // mismatch case 记录了不匹配字段
        CaseResult fail = stage.cases().stream()
                .filter(c -> c.caseId().equals("f1")).findFirst().orElseThrow();
        assertThat(fail.mismatches()).anyMatch(m -> m.contains("intent"));
    }

    @Test
    void loadFile_loadsInjectionJsonFromClasspath() {
        EvalExecutor executor = new EvalExecutor(fakeExecutor());

        EvalFile file = executor.loadFile("eval/injection.json");

        assertThat(file.stage()).isEqualTo("injection");
        assertThat(file.cases()).hasSize(8);
        EvalCase inj001 = file.cases().stream()
                .filter(c -> c.id().equals("inj-001")).findFirst().orElseThrow();
        assertThat(inj001.input()).contains("ignore previous");
        assertThat(inj001.expected().scenario()).isEqualTo("INJECTION");
        assertThat(inj001.expected().blocked()).isTrue();
    }

    @Test
    void parseFile_setsSourceFromResolver() {
        // 2026-09-17 Nacos 动态化：内容来自 EvalContentResolver（Nacos 实时拉取），source 随报告透传
        EvalExecutor executor = new EvalExecutor(fakeExecutor());
        String content = "{\"stage\":\"intent\",\"description\":\"Nacos 版黄金集\",\"cases\":[]}";
        EvalFile file = executor.parseFile("eval/intent.json", content, "nacos");

        assertThat(file.stage()).isEqualTo("intent");
        assertThat(file.source()).isEqualTo("nacos");
        assertThat(file.cases()).isEmpty();
    }

    @Test
    void parseFile_invalidContent_throwsIllegalState() {
        // Nacos 编辑坏 JSON → 解析失败抛 IllegalStateException（控制器逐 stage 降级跳过）
        EvalExecutor executor = new EvalExecutor(fakeExecutor());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> executor.parseFile("eval/intent.json", "not json", "nacos"));
    }

    @Test
    void runStage_carriesFileSourceIntoReport() {
        EvalFile file = new EvalFile("intent", "Nacos 版", List.of(passCase("p1", "分析 Q3")), "nacos");
        EvalExecutor executor = new EvalExecutor(fakeExecutor());

        EvalReport report = executor.run(List.of(file));

        assertThat(report.stages().get(0).source()).isEqualTo("nacos");
    }
}
