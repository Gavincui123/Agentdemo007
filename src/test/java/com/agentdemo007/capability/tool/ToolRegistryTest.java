package com.agentdemo007.capability.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具注册表测试（第四层·工具元数据管理）。
 *
 * <p>验收（Phase 9）：管理可用工具元数据（名称/描述/参数 Schema），注册/查询/注销线程安全。
 */
class ToolRegistryTest {

    private final ToolRegistry registry = new ToolRegistry();

    private static ToolDefinition tool(String name) {
        return new ToolDefinition(
                name, name + " 描述",
                List.of(new ParamSpec("arg", String.class, true)),
                input -> Optional.of(Map.of("arg", input)),
                args -> "ok:" + args.get("arg"));
    }

    @Test
    void registerAndGet() {
        registry.register(tool("arithmetic"));
        assertThat(registry.has("arithmetic")).isTrue();
        assertThat(registry.get("arithmetic")).isNotNull();
        assertThat(registry.get("arithmetic").name()).isEqualTo("arithmetic");
    }

    @Test
    void get_unknown_returnsNull() {
        assertThat(registry.get("nope")).isNull();
        assertThat(registry.has("nope")).isFalse();
    }

    @Test
    void all_returnsRegistered() {
        registry.register(tool("a"));
        registry.register(tool("b"));
        assertThat(registry.all()).hasSize(2);
        assertThat(registry.all()).extracting(ToolDefinition::name).contains("a", "b");
    }

    @Test
    void unregister_removes() {
        registry.register(tool("arithmetic"));
        registry.unregister("arithmetic");
        assertThat(registry.has("arithmetic")).isFalse();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void duplicateRegister_overwrites() {
        registry.register(tool("arithmetic"));
        ToolDefinition second = new ToolDefinition(
                "arithmetic", "新版描述", List.of(),
                input -> Optional.empty(), args -> "v2");
        registry.register(second);
        assertThat(registry.get("arithmetic").description()).isEqualTo("新版描述");
        assertThat(registry.all()).hasSize(1);
    }
}
