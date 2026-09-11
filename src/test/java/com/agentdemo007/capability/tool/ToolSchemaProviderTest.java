package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolCircuitBreaker;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolSchemaProvider 单测（② Slice 3·schema+executor 单源反射）。
 *
 * <p>验"我的反射代码"把 4 个 @Tool beans 全部拾起（name 取自方法名，无 {@code @Tool(name=...)}）
 * + executors 键与 schema 键<b>一致</b>（避免"模型调到无 executor 工具→dispatch 跳过死工具"，
 * [[langchain4j-boot4-compat-findings]]：同源 binding 保证键一致）。{@link #schemasFor} 子集过滤
 * + null/空回退（per-route 工具子集，RoutePlan.required_tools）。
 *
 * <p>这是测本仓代码（反射装配），非 LC4j 框架（DefaultToolExecutor 的 JSON 解析/强转/反射
 * 由 {@link ToolCallExecutorTest} 间接证）。
 */
class ToolSchemaProviderTest {

    private final ToolSchemaProvider provider = new ToolSchemaProvider(
            List.of(new TriangleAreaTool(), new CircleAreaTool(),
                    new MultiplicationTool(), new ArithmeticTool()));

    @Test
    void allSchemas_containsFourTools_byMethodName() {
        List<String> names = provider.allSchemas().stream()
                .map(ToolSpecification::name).toList();
        assertThat(names).containsExactlyInAnyOrder("triangleArea", "circleArea", "table", "calculate");
    }

    @Test
    void executors_keysMatchSchemaNames() {
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(3, 30000, System::currentTimeMillis);
        var execs = provider.executors(breaker);
        List<String> schemaNames = provider.allSchemas().stream()
                .map(ToolSpecification::name).toList();
        assertThat(execs.keySet()).containsExactlyInAnyOrderElementsOf(schemaNames);
    }

    @Test
    void schemasFor_subsetFilters_unknownSkipped() {
        List<String> names = provider.schemasFor(List.of("triangleArea", "nope")).stream()
                .map(ToolSpecification::name).toList();
        assertThat(names).containsExactly("triangleArea");
    }

    @Test
    void schemasFor_nullOrEmpty_returnsEmpty() {
        assertThat(provider.schemasFor(null)).isEmpty();
        assertThat(provider.schemasFor(List.of())).isEmpty();
    }
}
