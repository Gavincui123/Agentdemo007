package com.agentdemo007.admin;

/**
 * 路由权重调权请求（Phase 15·T66 运维控制台入参，第四原则·强类型收口，非 Map）。
 *
 * @param modelId 目标模型标识（非空）
 * @param weight  新权重（非负；0 表示从加权抽样中抽干流量）
 */
public record RouteWeightRequest(String modelId, int weight) {
}
