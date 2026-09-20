package com.agentdemo007.eval;

import java.util.List;

/**
 * 评测运行进度快照（2026-09-18 评测异步化：POST 秒回 + GET /eval/progress 轮询）。
 *
 * <p>不可变快照——{@code EvalJobManager} 单写线程在每次阶段迁移时整体重建，HTTP 读线程随意读。
 *
 * @param runId           本次运行标识（短 UUID；idle 为 null）
 * @param running         是否执行中（完成后 false）
 * @param totalStages     总 stage 数（= 资源数）
 * @param completedStages 已完成 stage 数（含跳过）
 * @param currentStage    正在执行的 stage 名（空闲/完成为 null）
 * @param stages          已产出的分环节报告（按完成顺序，运行中即部分可见——进度页实时亮灯）
 * @param skipped         因数据缺失/解析失败被跳过的 stage 名（②每步降级，不杀整体）
 * @param failed          整体异常中止（区别于个别 stage 失败）
 * @param errorMessage    失败话术（failed=true 时非空）
 * @param report          最终聚合报告（仅完成后非空）
 * @param startedAtMs     启动时刻（epoch ms，前端据此实时计算运行时长）
 */
public record EvalProgress(
        String runId,
        boolean running,
        int totalStages,
        int completedStages,
        String currentStage,
        List<StageReport> stages,
        List<String> skipped,
        boolean failed,
        String errorMessage,
        EvalReport report,
        long startedAtMs) {

    /** 空闲态（应用启动后从未跑过 / 重启后）。 */
    public static EvalProgress idle() {
        return new EvalProgress(null, false, 0, 0, null, List.of(), List.of(), false, null, null, 0L);
    }
}
