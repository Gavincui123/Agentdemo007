package com.agentdemo007.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评测异步作业管理器单测（2026-09-18 评测异步化）。
 *
 * <p>同步 Executor（Runnable::run）直驱生命周期：启动→逐 stage 产出→终态报告聚合；
 * 单 stage 数据坏 → skipped 不杀整体（②每步降级）；重复启动 → false（CONFLICT 语义）；
 * 空闲态快照。resolver 用 Mockito 假桩（真桩路径属 {@link EvalContentResolverTest}）。
 */
class EvalJobManagerTest {

    private static EvalExecutor trivialExecutor() {
        return new EvalExecutor(context -> com.agentdemo007.common.pipeline.PipelineResult.ok("ok"));
    }

    @Test
    void start_syncRun_completesWithAggregatedReport() {
        EvalJobManager manager = new EvalJobManager(trivialExecutor(), localResolver(), Runnable::run);

        assertThat(manager.start(List.of("eval/injection.json", "eval/tool.json"))).isTrue();

        EvalProgress progress = manager.progress();
        assertThat(progress.running()).isFalse();
        assertThat(progress.failed()).isFalse();
        assertThat(progress.totalStages()).isEqualTo(2);
        assertThat(progress.completedStages()).isEqualTo(2);
        assertThat(progress.stages()).extracting(StageReport::stage)
                .containsExactly("injection", "tool");
        assertThat(progress.report()).isNotNull();
        assertThat(progress.report().totalCases()).isGreaterThan(0);
        assertThat(manager.running()).isFalse();
    }

    @Test
    void start_badStageData_skipped_notFatal() {
        // resolver 对 tool stage 返回坏 JSON → 解析失败 → 跳过该 stage，其余照常
        EvalContentResolver resolver = mock(EvalContentResolver.class);
        when(resolver.resolve("eval/injection.json"))
                .thenReturn(new EvalContentResolver.Resolved(localContent("injection"), "local"));
        when(resolver.resolve("eval/tool.json"))
                .thenReturn(new EvalContentResolver.Resolved("not json", "nacos"));
        EvalJobManager manager = new EvalJobManager(trivialExecutor(), resolver, Runnable::run);

        assertThat(manager.start(List.of("eval/injection.json", "eval/tool.json"))).isTrue();

        EvalProgress progress = manager.progress();
        assertThat(progress.failed()).isFalse();
        assertThat(progress.stages()).extracting(StageReport::stage).containsExactly("injection");
        assertThat(progress.skipped()).containsExactly("tool");
        assertThat(progress.completedStages()).isEqualTo(1);
        assertThat(progress.report()).isNotNull();
        assertThat(progress.report().totalCases()).isEqualTo(1); // 仅合成 injection 1 例（tool 被跳过）
    }

    @Test
    void start_whileRunning_returnsFalse() {
        EvalJobManager manager = new EvalJobManager(trivialExecutor(), localResolver(),
                task -> { /* 挂起不执行 */ });

        assertThat(manager.start(List.of("eval/injection.json"))).isTrue();
        assertThat(manager.start(List.of("eval/injection.json"))).isFalse();
        assertThat(manager.running()).isTrue();
    }

    @Test
    void progress_idle_beforeAnyRun() {
        EvalJobManager manager = new EvalJobManager(trivialExecutor(), localResolver(), Runnable::run);

        EvalProgress progress = manager.progress();

        assertThat(progress.running()).isFalse();
        assertThat(progress.runId()).isNull();
        assertThat(progress.report()).isNull();
        assertThat(progress.stages()).isEmpty();
    }

    private static EvalContentResolver localResolver() {
        return new EvalContentResolver(null, false, "DEFAULT_GROUP", "agentdemo-eval", 3000L);
    }

    private static String localContent(String stage) {
        return "{\"stage\":\"" + stage + "\",\"description\":\"t\",\"cases\":["
                + "{\"id\":\"c1\",\"input\":\"x\",\"expected\":{\"scenario\":\"NONE\"}}]}";
    }
}
