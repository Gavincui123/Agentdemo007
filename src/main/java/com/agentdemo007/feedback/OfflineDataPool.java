package com.agentdemo007.feedback;

import java.util.List;

/**
 * 离线数据池 seam（Phase 15·微调闭环数据层，§5.14 引擎无关内核）。
 *
 * <p>存储 {@link TrainingSample} 供 T68 {@code FineTuningPipeline} 拉取训练：
 * {@code store} 追加，{@code drain} 取出全部并清空（一次性消费，避免重复训练）。
 * 形态对齐 {@code ConfigWriter}/{@code MessagePublisher}：seam 在前，dev 内存占位，prod 后接
 * （JPA/对象存储/文件）。
 *
 * <p>②每步降级：实现可抛异常，调用方（{@link FeedbackCollector}）吞而不影响主链路（反馈是副信道）。
 */
public interface OfflineDataPool {

    /** 追加一条训练样本到池。 */
    void store(TrainingSample sample);

    /** 取出全部样本并清空池（供训练拉取）。 */
    List<TrainingSample> drain();

    /** 当前池内样本数。 */
    int size();
}
