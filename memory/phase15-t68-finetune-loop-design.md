---
name: phase15-t68-finetune-loop-design
description: Phase 15 T68 微调闭环训练+注册：FineTuningPipeline编排drain→TrainingJobRunner→ModelRegistrar+RegistrationWriter seam+registerModel热注册（533测试）
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-07T11:08:45.941Z
---

Phase 15 T68（微调闭环·训练+注册，533 测试）：

**闭环收口**（接 [[phase15-t67-feedback-data-layer-design]]）：FineTuningPipeline.run() → drain OfflineDataPool → TrainingJobRunner.submit(samples) → ModelRegistrar.register(trained) → ModelConfigCenter.registerModel(meta) 内存热注册（可被路由选中）。

**seam 三件套**（§5.14 引擎无关，对齐 ConfigWriter/MessagePublisher 形态）：
- `TrainingJobRunner`（functional）：`TrainedModel submit(List<TrainingSample>)`。NO_OP 返回占位模型（id 含样本数，不用 Math.random）。prod 装外部训练平台适配器。
- `RegistrationWriter`（functional）：`boolean writeRegistration(TrainedModel)`。NO_OP=false。prod 装配 NacosConfigWriter publishConfig。
- 注意：不复用 T66 ConfigWriter（writeModelWeight 是 weight-specific functional interface；加方法会破坏 T66 lambda 测试）→ 每个写回操作一个独立 seam。

**ModelConfigCenter.registerModel(ModelMetadata)**（synchronized，④收口）：直接 registry.register（同 id 覆盖），使新模型立即对 ModelSelector/ModelRouter 可见可选中。applyWeight 是既有模型改权重，registerModel 是注册新模型——两条热生效路径，都 synchronized + 都有 prod 写回 seam。

**ModelRegistrar**（@Component，NO_OP-overload 装配）：register(trained) → builder 建 ModelMetadata → center.registerModel（内存热，恒成功）→ writeBackBestEffort（try/catch ②降级，写回失败不回滚内存、返回 false）。对齐 T66 RouteWeightController 的"内存先应用+写回 best-effort"模式。

**FineTuningPipeline**（plain class + @Bean FineTuneConfig）：Optional<TrainedModel> run()。空池→empty；训练/注册异常 try/catch→empty（②降级不抛）。依赖 OfflineDataPool+TrainingJobRunner+ModelRegistrar 三 seam，全可注入替身。

**FineTuneConfig**：@Bean FineTuningPipeline(pool, runner, registrar) + @ConditionalOnMissingBean TrainingJobRunner NO_OP + RegistrationWriter NO_OP（dev）。

**Why**：满足验收"微调闭环：反馈→数据池→注册新模型→配置中心热加载→可被路由选中"——registerModel 内存热生效使 ModelRouter 即刻可选中新模型；写回 seam 为 prod 跨实例传播预留。
**How to apply**：同类"外部任务编排+热注册"复用 drain→seam.submit→register(内存热+写回best-effort) 模式；每写回操作独立 functional seam（勿合并破坏既有 lambda 测试）。
