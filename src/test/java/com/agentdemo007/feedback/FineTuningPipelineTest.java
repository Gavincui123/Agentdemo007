package com.agentdemo007.feedback;

import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.config.ModelConfigSnapshot;
import com.agentdemo007.gateway.registry.ModelRegistry;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 微调流水线测试（Phase 15·T68 微调闭环·编排）。
 *
 * <p>{@link FineTuningPipeline#run} 闭环：drain 数据池 → 外部训练 seam（{@link TrainingJobRunner}）
 * → {@link ModelRegistrar} 注册热生效。空池不训练；训练 seam 抛异常吞而不抛（②降级返回空）。
 */
class FineTuningPipelineTest {

    private static final TrainedModel TRAINED =
            new TrainedModel("ft-x", "p", "http://x", Set.of("REASONING"), 3);

    private TrainingSample sample(String traceId) {
        return new TrainingSample(traceId, "s", "prompt", "reply",
                FeedbackLabel.POSITIVE, null, OffsetDateTime.now());
    }

    private ModelConfigCenter emptyCenter() {
        ModelConfigCenter center = new ModelConfigCenter(
                () -> new ModelConfigSnapshot(List.of(), List.of(), null, null), new ModelRegistry());
        center.refresh();
        return center;
    }

    @Test
    void run_drainsPoolSubmitsAndRegisters_returnsTrained() {
        InMemoryOfflineDataPool pool = new InMemoryOfflineDataPool();
        pool.store(sample("t1"));
        pool.store(sample("t2"));
        List<List<TrainingSample>> captured = new ArrayList<>();
        TrainingJobRunner runner = samples -> { captured.add(samples); return TRAINED; };
        ModelConfigCenter center = emptyCenter();
        ModelRegistrar registrar = new ModelRegistrar(center, RegistrationWriter.NO_OP);
        FineTuningPipeline pipeline = new FineTuningPipeline(pool, runner, registrar);

        Optional<TrainedModel> result = pipeline.run();

        assertThat(result).contains(TRAINED);
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0)).hasSize(2); // drain 的样本传给训练 seam
        assertThat(pool.size()).isZero(); // 已 drain 清空
        // 注册热生效：新模型已在注册表，可被路由选中
        assertThat(center.registry().get("ft-x")).isPresent();
    }

    @Test
    void run_emptyPool_returnsEmpty_doesNotTrainOrRegister() {
        InMemoryOfflineDataPool pool = new InMemoryOfflineDataPool();
        List<List<TrainingSample>> captured = new ArrayList<>();
        TrainingJobRunner runner = samples -> { captured.add(samples); return TRAINED; };
        ModelConfigCenter center = emptyCenter();
        ModelRegistrar registrar = new ModelRegistrar(center, RegistrationWriter.NO_OP);
        FineTuningPipeline pipeline = new FineTuningPipeline(pool, runner, registrar);

        Optional<TrainedModel> result = pipeline.run();

        assertThat(result).isEmpty(); // 无数据不训练
        assertThat(captured).isEmpty(); // 未提交训练
        assertThat(center.registry().all()).isEmpty(); // 未注册
    }

    @Test
    void run_runnerThrows_returnsEmpty_bestEffort_doesNotThrow() {
        InMemoryOfflineDataPool pool = new InMemoryOfflineDataPool();
        pool.store(sample("t1"));
        TrainingJobRunner throwing = samples -> { throw new RuntimeException("训练平台不可达"); };
        ModelConfigCenter center = emptyCenter();
        ModelRegistrar registrar = new ModelRegistrar(center, RegistrationWriter.NO_OP);
        FineTuningPipeline pipeline = new FineTuningPipeline(pool, throwing, registrar);

        assertThatCode(() -> pipeline.run()).doesNotThrowAnyException();
        assertThat(pipeline.run()).isEmpty(); // ②降级：训练失败返回空
        assertThat(center.registry().all()).isEmpty(); // 训练失败→未注册
    }
}
