package com.agentdemo007.eval;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineExecutor;
import com.agentdemo007.common.pipeline.PipelineResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 黄金数据集加载 + 全量跑通冒烟（Phase 15·T72 ③环节测评 golden data 验证 + 生产冒烟·单元级）。
 *
 * <p>加载 dev-plan §Phase15 标准 8 stage 黄金集 + 红队扩展 stage（rag-redteam，污染语料/召回冲突
 * 演示；真库模式经 /eval/run stage 过滤单独执行），断言：每文件 stage 名与预期一致、cases 非空、
 * 每例 id/input/expected 非空
 * （解析兼容性回归守卫——验 EvalFile/EvalCase/EvalExpected 跨全真实数据集可用，非仅 injection 单文件）；
 * 并以 trivial 执行器经 {@link EvalExecutor#run} 全量跑通，断言 8 stage 名齐全 + totalCases 聚合正确
 * （评估器跨全真实数据集端到端可用，生产冒烟·单元级代理——真实高并发/故障注入冒烟属部署门禁）。
 *
 * <p>trivial 执行器恒返回 ok——只验加载+跑通+收口，不验通过率（通过率依赖真实流水线，属各 stage 既有单测）。
 * 本测试为既有 golden 数据的 characterization 守卫（立即通过即合法回归守卫，TDD skill 允许）。
 */
class GoldenSuiteTest {

    private static final List<String> STANDARD_SUITE = List.of(
            "eval/injection.json", "eval/intent.json", "eval/routing.json", "eval/rag.json",
            "eval/rag-redteam.json",
            "eval/tool.json", "eval/hitl.json", "eval/degradation.json", "eval/audit.json");

    private static final List<String> EXPECTED_STAGES = List.of(
            "injection", "intent", "routing", "rag", "rag-redteam", "tool", "hitl", "degradation", "audit");

    private EvalExecutor executorWithTrivialPipeline() {
        PipelineExecutor trivial = new PipelineExecutor() {
            @Override
            public PipelineResult run(PipelineContext context) {
                return PipelineResult.ok("ok");
            }
        };
        return new EvalExecutor(trivial);
    }

    @Test
    void standardSuite_loadsAndRunsAllStages() {
        EvalExecutor executor = executorWithTrivialPipeline();

        List<EvalFile> files = new ArrayList<>();
        for (String resource : STANDARD_SUITE) {
            files.add(executor.loadFile(resource));
        }

        // 解析兼容性：每文件 stage 名与预期一致 + cases 非空 + 每例字段非空
        for (int i = 0; i < files.size(); i++) {
            EvalFile file = files.get(i);
            assertThat(file.stage()).isEqualTo(EXPECTED_STAGES.get(i));
            assertThat(file.cases()).isNotEmpty();
            for (EvalCase c : file.cases()) {
                assertThat(c.id()).isNotBlank();
                assertThat(c.input()).isNotNull();
                assertThat(c.expected()).isNotNull();
            }
        }

        // 全量跑通冒烟：9 stage 名齐全 + totalCases 聚合正确
        EvalReport report = executor.run(files);
        assertThat(report.stages()).extracting(StageReport::stage)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_STAGES);
        int expectedTotal = files.stream().mapToInt(f -> f.cases().size()).sum();
        assertThat(report.totalCases()).isEqualTo(expectedTotal);
    }
}
