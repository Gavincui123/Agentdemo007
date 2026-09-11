package com.agentdemo007.feedback;

/**
 * 配置中心写回·模型注册 seam（Phase 15·T68，§5.14 引擎无关内核）。
 *
 * <p>domain-typed 写回：{@link #writeRegistration} 以 {@link TrainedModel} 入参，不暴露配置中心内容格式。
 * prod Nacos 实现负责把新模型合并进配置并 {@code publishConfig}，触发各实例
 * {@link ModelConfigCenter#refresh()} 收敛——此后该端点可被路由选中。dev {@link #NO_OP} 返回 false，
 * 热生效走 {@link ModelConfigCenter#registerModel} 内存路径兜底。
 *
 * <p>②每步降级：实现可抛异常，调用方（{@link ModelRegistrar}）吞而不回滚内存、不抛。
 * 形态对齐 {@code ConfigWriter}（T66 调权写回）：seam 在前，dev 占位，prod 后接。
 */
@FunctionalInterface
public interface RegistrationWriter {

    /**
     * 写回新模型注册到配置中心。
     *
     * @param model 训练产出的新模型
     * @return 持久化成功返回 true；未持久化（dev 占位 / 失败）返回 false
     */
    boolean writeRegistration(TrainedModel model);

    /** dev 占位：不持久化，仅返回 false（热生效走 ModelConfigCenter.registerModel 内存路径）。 */
    RegistrationWriter NO_OP = model -> false;
}
