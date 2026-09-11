package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolRecoverableException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 圆形面积工具单测（② Slice 3 退役手撸路径后·直接测 @Tool 方法业务逻辑）。
 *
 * <p>退役映射：旧测经手撸 {@code ToolExecutor}+{@code CircleAreaDetector}+{@code ParamParser}
 * +{@code SchemaValidator} 走关键词检测闭环，已退役——LC4j function-calling 由模型出
 * {@code tool_calls}，{@link dev.langchain4j.service.tool.DefaultToolExecutor} 反射调本方法
 * （plumbing 详见 {@link ToolCallExecutorTest}）。本测聚焦 @Tool 方法自身契约：
 * π×半径² BigDecimal 高精度（4 位小数）+ 负半径抛 {@link ToolRecoverableException}。
 *
 * <p>详见 [[routeplan-design]] 缺口实现优先级①。
 */
class CircleAreaToolTest {

    private final CircleAreaTool tool = new CircleAreaTool();

    @Test
    void circleArea_radius5_computed() {
        assertThat(tool.circleArea(5)).isEqualTo("78.5398");
    }

    @Test
    void circleArea_unitRadius_computed() {
        assertThat(tool.circleArea(1)).isEqualTo("3.1416");
    }

    @Test
    void circleArea_negativeRadius_throwsRecoverable() {
        assertThatThrownBy(() -> tool.circleArea(-1))
                .isInstanceOf(ToolRecoverableException.class);
    }
}
