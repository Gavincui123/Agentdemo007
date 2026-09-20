package com.agentdemo007.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 评测异步作业管理器（2026-09-18 评测异步化）。
 *
 * <p>背景：全量黄金集逐例驱动真实流水线（每例真实 LLM 调用），同步 HTTP 长请求曾拖爆前端
 * axios 30s 超时（话术"网络连接失败"）且全程无进度。现改为：POST /eval/run 秒回启动作业 →
 * 单线程后台逐 stage 执行（Nacos 实时拉数据 → 解析 → 跑该 stage）→ GET /eval/progress 轮询
 * {@link EvalProgress} 快照（进度条 + 分环节结果实时亮灯）。同一时刻至多一个评测作业
 * （重复启动 → CONFLICT 话术）；作业状态进程内单份，页面刷新后拉进度无缝续看。
 *
 * <p>②每步降级：单 stage 数据缺失/解析失败 → 跳过记入 {@code skipped}，其余照常；
 * 整体异常 → {@code failed=true} + 话术，不影响下一次启动。
 *
 * <p>plain class + @Bean 工厂（{@code EvalConfig}）：Executor 可注入——生产为 daemon 单线程，
 * 单测传 {@code Runnable::run} 同步直跑（start 后即终态）。
 */
public class EvalJobManager {

    private static final Logger log = LoggerFactory.getLogger(EvalJobManager.class);

    private final EvalExecutor executor;
    private final EvalContentResolver resolver;
    private final Executor runner;
    private final AtomicReference<EvalProgress> state = new AtomicReference<>(EvalProgress.idle());
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EvalJobManager(EvalExecutor executor, EvalContentResolver resolver, Executor runner) {
        this.executor = executor;
        this.resolver = resolver;
        this.runner = runner;
    }

    /** 启动评测作业；已有作业在跑 → false（调用方转 CONFLICT 话术）。 */
    public boolean start(List<String> resources) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        String runId = UUID.randomUUID().toString().substring(0, 8);
        long startedAtMs = System.currentTimeMillis();
        state.set(new EvalProgress(runId, true, resources.size(), 0, null,
                List.of(), List.of(), false, null, null, startedAtMs));
        runner.execute(() -> runAll(resources, runId, startedAtMs));
        return true;
    }

    /** 当前进度快照（不可变，HTTP 线程随意读）。 */
    public EvalProgress progress() {
        return state.get();
    }

    /** 是否有作业在跑。 */
    public boolean running() {
        return running.get();
    }

    private void runAll(List<String> resources, String runId, long startedAtMs) {
        List<StageReport> stages = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try {
            for (String resource : resources) {
                String stageName = EvalContentResolver.stageOf(resource);
                state.set(snapshot(runId, resources.size(), startedAtMs, stages, skipped, stageName, null));
                try {
                    EvalContentResolver.Resolved resolved = resolver.resolve(resource);
                    EvalFile file = executor.parseFile(resource, resolved.content(), resolved.source());
                    stages.add(executor.runStage(file));
                } catch (Exception e) {
                    // ②每步降级：单 stage 数据缺失/解析失败 → 跳过，其余照常
                    log.warn("评测 stage 加载/执行失败，跳过：resource={} reason={}", resource, e.getMessage());
                    skipped.add(stageName);
                }
                state.set(snapshot(runId, resources.size(), startedAtMs, stages, skipped, null, null));
            }
            EvalReport report = aggregate(stages);
            state.set(new EvalProgress(runId, false, resources.size(), stages.size(), null,
                    List.copyOf(stages), List.copyOf(skipped), false, null, report, startedAtMs));
            log.info("评测完成：runId={} stages={} skipped={} 通过={}/{}",
                    runId, stages.size(), skipped.size(), report.totalPassed(), report.totalCases());
        } catch (Exception e) {
            state.set(new EvalProgress(runId, false, resources.size(), stages.size(), null,
                    List.copyOf(stages), List.copyOf(skipped), true, "评测执行异常，已中止", null, startedAtMs));
            log.error("评测整体异常中止：runId={}", runId, e);
        } finally {
            running.set(false);
        }
    }

    private EvalProgress snapshot(String runId, int totalStages, long startedAtMs, List<StageReport> stages,
                                  List<String> skipped, String currentStage, EvalReport report) {
        return new EvalProgress(runId, true, totalStages, stages.size(), currentStage,
                List.copyOf(stages), List.copyOf(skipped), false, null, report, startedAtMs);
    }

    private static EvalReport aggregate(List<StageReport> stages) {
        int passed = stages.stream().mapToInt(StageReport::passed).sum();
        int total = stages.stream().mapToInt(StageReport::total).sum();
        return new EvalReport(List.copyOf(stages), passed, total);
    }
}
