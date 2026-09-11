package com.agentdemo007.capability.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 LC4j core 的 @Tool/@P → function schema 生成在 Boot 4 / Jackson 3 共存环境可用。
 *
 * <p>option B 地基：用 LC4j 原生注解生成 function schema（不手写 detector）。
 * option A（AiServices）内部亦复用此 schema 生成，故 A/B 共有、不绑死。
 * 详见 [[routeplan-design]] + [[langchain4j-boot4-compat-findings]]。
 */
class Lc4jToolSchemaTest {

    @Test
    void generatesSpecFromAnnotatedTriangleArea() {
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(new TriangleAreaTool());
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).name()).isEqualTo("triangleArea");
    }
}
