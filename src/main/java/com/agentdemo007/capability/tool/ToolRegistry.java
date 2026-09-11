package com.agentdemo007.capability.tool;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表（第四层·可用工具元数据管理）。
 *
 * <p>线程安全（{@link ConcurrentHashMap}）：register/get/has/unregister/all。
 * 重复注册同名工具以覆盖语义（后注册胜出，支持热更新）；不抛异常以免阻塞装配。
 * {@code @Component}：由 ToolConfig 在启动期注册示例工具，后续可经配置中心/微调闭环动态注册。
 */
@Component
public class ToolRegistry {

    private final ConcurrentHashMap<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    public void register(ToolDefinition tool) {
        if (tool == null || tool.name() == null) {
            return;
        }
        tools.put(tool.name(), tool);
    }

    public ToolDefinition get(String name) {
        if (name == null) {
            return null;
        }
        return tools.get(name);
    }

    public boolean has(String name) {
        return name != null && tools.containsKey(name);
    }

    public Collection<ToolDefinition> all() {
        return tools.values();
    }

    public void unregister(String name) {
        if (name != null) {
            tools.remove(name);
        }
    }

    public void clear() {
        tools.clear();
    }
}
