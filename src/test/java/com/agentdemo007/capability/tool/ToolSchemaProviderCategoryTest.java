package com.agentdemo007.capability.tool;

import com.agentdemo007.capability.business.MockPolicyQueryService;
import com.agentdemo007.capability.business.OrderQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.agentdemo007.capability.tool.ToolCategory.COMPUTE;
import static com.agentdemo007.capability.tool.ToolCategory.RAG;
import static com.agentdemo007.capability.tool.ToolCategory.RUNTIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * {@link ToolSchemaProvider} 通道元数据单测（Slice 2·[[business-tools-workflow-dag]] §2.2）。
 *
 * <p>验 {@code @ToolChannel} 注解经反射入 {@code ToolBinding.category}，{@link ToolSchemaProvider#categoryOf} /
 * {@link ToolSchemaProvider#categoryMap} 暴露：
 * <ul>
 *   <li>{@code @ToolChannel(RUNTIME)}（OrderQueryTool）→ RUNTIME（外部系统高置信，进 RunTime_* 通道）；</li>
 *   <li>{@code @ToolChannel(RAG)}（ReturnPolicyTool）→ RAG（知识库政策，进 RAG_Messages 带 citation）；</li>
 *   <li>无注解（TriangleAreaTool）→ COMPUTE 默认（向后兼容既有计算工具，进 Tool_message）。</li>
 * </ul>
 * 用真实 @Tool 包装（非 fixture）——一并覆盖包装类注解正确性（Slice 5 真模型调用前置）。
 */
class ToolSchemaProviderCategoryTest {

    private ToolSchemaProvider providerWithBusinessTools() {
        return new ToolSchemaProvider(List.of(
                new OrderQueryTool(new OrderQueryService()),       // @ToolChannel(RUNTIME)
                new ReturnPolicyTool(new MockPolicyQueryService()), // @ToolChannel(RAG)
                new TriangleAreaTool()));                          // 无注解 → COMPUTE
    }

    @Test
    void categoryOf_runtimeAnnotated_returnsRuntime() {
        ToolSchemaProvider p = providerWithBusinessTools();
        assertThat(p.categoryOf("queryOrder")).isEqualTo(RUNTIME);
    }

    @Test
    void categoryOf_ragAnnotated_returnsRag() {
        ToolSchemaProvider p = providerWithBusinessTools();
        assertThat(p.categoryOf("queryReturnPolicy")).isEqualTo(RAG);
    }

    @Test
    void categoryOf_noAnnotation_defaultsCompute() {
        ToolSchemaProvider p = providerWithBusinessTools();
        assertThat(p.categoryOf("triangleArea")).isEqualTo(COMPUTE);
    }

    @Test
    void categoryOf_unknown_defaultsCompute() {
        ToolSchemaProvider p = new ToolSchemaProvider(List.of());
        assertThat(p.categoryOf("doesNotExist")).isEqualTo(COMPUTE);
    }

    @Test
    void categoryMap_containsAllChannels() {
        ToolSchemaProvider p = providerWithBusinessTools();
        assertThat(p.categoryMap())
                .contains(entry("queryOrder", RUNTIME),
                        entry("queryReturnPolicy", RAG),
                        entry("triangleArea", COMPUTE));
    }

    @Test
    void categoryMap_keysAlignWithSchemas() {
        // schemas 与 categoryMap 同源（同批 binding），键一致——模型只能调到有 category 的工具
        ToolSchemaProvider p = providerWithBusinessTools();
        assertThat(p.categoryMap().keySet()).isEqualTo(p.allSchemas().stream()
                .map(s -> s.name()).collect(java.util.stream.Collectors.toSet()));
    }
}
