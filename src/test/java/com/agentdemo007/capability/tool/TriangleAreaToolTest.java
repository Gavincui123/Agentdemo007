package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolRecoverableException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三角形面积工具单测（② Slice 3 退役手撸路径后·直接测 @Tool 方法业务逻辑）。
 *
 * <p>退役映射：旧测经手撸 {@code ToolExecutor}+{@code TriangleAreaDetector}+{@code ParamParser}
 * +{@code SchemaValidator} 走关键词检测闭环（detect→parse→validate→exec），已退役——LC4j
 * function-calling 由模型出结构化 {@code tool_calls}（含 JSON 参数），{@link dev.langchain4j.service.tool.DefaultToolExecutor}
 * 原生 JSON 解析+强转+反射调本方法（plumbing 详见 {@link ToolCallExecutorTest}）。
 *
 * <p>本测聚焦 @Tool 方法自身契约：底×高÷2 BigDecimal 高精度 + 负值抛 {@link ToolRecoverableException}
 * （DefaultToolExecutor 原生吞 @Tool 异常→消息当结果返回，不透传 ToolExecutionStep，
 * [[langchain4j-boot4-compat-findings]]：自纠正开箱即用）。
 *
 * <p>详见 [[routeplan-design]] 缺口实现优先级①：starter calc tools。
 */
class TriangleAreaToolTest {

    private final TriangleAreaTool tool = new TriangleAreaTool();

    @Test
    void triangleArea_baseHeight_computed() {
        assertThat(tool.triangleArea(3, 4)).isEqualTo("6");
    }

    @Test
    void triangleArea_decimalDims_computed() {
        assertThat(tool.triangleArea(3.5, 2)).isEqualTo("3.5");
    }

    @Test
    void triangleArea_negativeDims_throwsRecoverable() {
        assertThatThrownBy(() -> tool.triangleArea(-1, 4))
                .isInstanceOf(ToolRecoverableException.class);
    }
}
