package com.agentdemo007.feedback;

import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.config.ModelMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 模型注册器（Phase 15·T68 微调闭环·注册半环）。
 *
 * <p>把训练产出的 {@link TrainedModel} 注册到 {@link ModelConfigCenter}（内存热生效，立即可被路由选中）
 * + 经 {@link RegistrationWriter} 写回配置中心（best-effort，②降级：写回失败不回滚内存、不抛）。
 *
 * <p>满足验收"注册新模型→配置中心热加载→可被路由选中"：内存 registerModel 使 {@code ModelSelector}/
 * {@code ModelRouter} 即刻可见；写回 seam 为 prod 跨实例传播预留。
 *
 * <p>NO_OP-overload 装配：单参构造委托 NO_OP 写回，保全部既有测试构造；@Autowired 双参构造接收 Spring 注入的
 * {@link ModelConfigCenter} + {@link RegistrationWriter}（dev 经 {@code FineTuneConfig} 给 NO_OP）。
 */
@Component
public class ModelRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistrar.class);

    private final ModelConfigCenter configCenter;
    private final RegistrationWriter writer;

    public ModelRegistrar(ModelConfigCenter configCenter) {
        this(configCenter, RegistrationWriter.NO_OP);
    }

    @Autowired
    public ModelRegistrar(ModelConfigCenter configCenter, RegistrationWriter writer) {
        this.configCenter = configCenter;
        this.writer = writer;
    }

    /**
     * 注册训练产出的新模型：内存热注册 → 写回配置中心。
     *
     * @param trained 训练产出描述
     * @return 写回是否持久化（内存热注册恒成功；写回失败返回 false，②降级不回滚）
     */
    public boolean register(TrainedModel trained) {
        if (trained == null || trained.modelId() == null || trained.modelId().isBlank()) {
            return false;
        }
        ModelMetadata meta = ModelMetadata.builder(trained.modelId())
                .provider(trained.provider())
                .endpoint(trained.endpoint())
                .tags(trained.tags() != null ? trained.tags() : Set.of())
                .weight(trained.weight())
                .build();
        configCenter.registerModel(meta); // 内存热生效（立即可被路由选中）
        return writeBackBestEffort(trained);
    }

    /** 写回配置中心（best-effort，②降级：异常不回滚内存、不抛）。 */
    private boolean writeBackBestEffort(TrainedModel trained) {
        try {
            return writer.writeRegistration(trained);
        } catch (Exception e) {
            log.warn("模型注册写回配置中心失败（已内存热生效，不回滚）：modelId={} reason={}",
                    trained.modelId(), e.getMessage());
            return false;
        }
    }
}
