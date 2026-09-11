package com.agentdemo007.feedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 微调流水线（Phase 15·T68 微调闭环·编排器）。
 *
 * <p>闭环编排：drain {@link OfflineDataPool} → 经 {@link TrainingJobRunner} 提交外部训练 →
 * 经 {@link ModelRegistrar} 注册热生效。空池不训练；训练/注册异常吞而不阻塞（②降级返回空）。
 *
 * <p>满足验收"微调闭环：反馈→数据池→注册新模型→配置中心热加载→可被路由选中"：
 * drain 取 T67 收集的样本 → 训练 seam 产出新模型 → ModelRegistrar 内存热注册（可被路由选中）+
 * 写回 seam 跨实例传播。
 *
 * <p>plain class + @Bean 工厂（{@code FineTuneConfig}），依赖 {@link OfflineDataPool} +
 * {@link TrainingJobRunner} + {@link ModelRegistrar} 三 seam/组件，全部可注入测试替身。
 */
public class FineTuningPipeline {

    private static final Logger log = LoggerFactory.getLogger(FineTuningPipeline.class);

    private final OfflineDataPool pool;
    private final TrainingJobRunner runner;
    private final ModelRegistrar registrar;

    public FineTuningPipeline(OfflineDataPool pool, TrainingJobRunner runner, ModelRegistrar registrar) {
        this.pool = pool;
        this.runner = runner;
        this.registrar = registrar;
    }

    /**
     * 执行一轮微调闭环：drain → 训练 → 注册。
     *
     * @return 训练产出（已注册热生效）；空池或训练失败返回 {@link Optional#empty()}（②降级不抛）
     */
    public Optional<TrainedModel> run() {
        List<TrainingSample> samples = pool.drain();
        if (samples.isEmpty()) {
            log.debug("微调闭环跳过：数据池为空，无可训练样本");
            return Optional.empty();
        }
        try {
            TrainedModel trained = runner.submit(samples);
            registrar.register(trained);
            log.info("微调闭环完成：samples={} modelId={} endpoint={}",
                    samples.size(), trained.modelId(), trained.endpoint());
            return Optional.of(trained);
        } catch (Exception e) {
            log.warn("微调训练失败（②降级，返回空）：samples={} reason={}",
                    samples.size(), e.getMessage());
            return Optional.empty();
        }
    }
}
