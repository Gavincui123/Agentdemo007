package com.agentdemo007.feedback;

import java.util.List;
import java.util.Set;

/**
 * 外部训练任务 seam（Phase 15·T68，§5.14 引擎无关内核）。
 *
 * <p>编排外部训练平台：提交 {@link TrainingSample} 集 → 返回训练产出的 {@link TrainedModel}
 * （新模型标识 + 端点）。dev {@link #NO_OP} 返回占位模型（不触达真实平台）；prod 装配外部训练适配器
 * （提交任务→轮询→返回部署端点）。形态对齐 {@code ConfigWriter}/{@code RegistrationWriter}。
 *
 * <p>②每步降级：实现可抛异常，调用方（{@link FineTuningPipeline}）吞而不阻塞闭环。
 */
@FunctionalInterface
public interface TrainingJobRunner {

    /**
     * 提交训练样本集，返回训练产出的新模型描述。
     *
     * @param samples 离线数据池 drain 出的训练样本
     * @return 训练产出（新模型标识 + 端点）
     */
    TrainedModel submit(List<TrainingSample> samples);

    /** dev 占位：不触达训练平台，返回 id 含样本数的占位模型。 */
    TrainingJobRunner NO_OP = samples -> new TrainedModel(
            "fine-tuned-noop-" + samples.size(), "noop", "http://noop", Set.of(), 1);
}
