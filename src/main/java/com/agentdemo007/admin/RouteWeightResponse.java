package com.agentdemo007.admin;

/**
 * 路由权重调权响应（Phase 15·T66 运维控制台出参，第四原则·强类型收口，非 Map）。
 *
 * @param modelId   调权目标模型标识
 * @param weight    应用的权重
 * @param applied   是否已内存热生效（注册表立见新权重）
 * @param persisted 是否已持久化到配置中心（dev NO_OP / 写回失败 → false）
 */
public record RouteWeightResponse(String modelId, int weight, boolean applied, boolean persisted) {
}
