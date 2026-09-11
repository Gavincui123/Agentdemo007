package com.agentdemo007.capability.tool;

/**
 * 工具参数规格（第四层·JSON Schema 的强类型等价，§5.14 收口：不引外部 JSON-Schema 库）。
 *
 * <p>每个参数声明名称、Java 类型、是否必填，由 {@link SchemaValidator} 据此校验运行期参数。
 * Phase 9 内置默认；后续可由 Nacos 热更新覆盖（随 NacosModelConfigSource）。
 */
public record ParamSpec(String name, Class<?> type, boolean required) {
}
