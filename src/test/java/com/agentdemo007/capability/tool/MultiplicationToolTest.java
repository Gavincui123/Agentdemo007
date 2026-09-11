package com.agentdemo007.capability.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 九九乘法表工具单测（② Slice 3 退役手撸路径后·直接测 @Tool 方法业务逻辑）。
 *
 * <p>退役映射：旧测经手撸 {@code ToolExecutor}+{@code MultiplicationTableDetector}
 * 走关键词检测闭环（无参工具），已退役——LC4j function-calling 由模型出 {@code tool_calls}
 * （无参），{@link dev.langchain4j.service.tool.DefaultToolExecutor} 反射调本无参方法
 * （plumbing 详见 {@link ToolCallExecutorTest}）。本测聚焦 @Tool 方法自身契约：
 * 下三角 9 行 + 角点（{@code 1x1=1} / {@code 9x9=81}）。
 *
 * <p>详见 [[routeplan-design]] 缺口实现优先级①。
 */
class MultiplicationToolTest {

    private final MultiplicationTool tool = new MultiplicationTool();

    @Test
    void table_prints9Rows_containsCorners() {
        String table = tool.table();
        assertThat(table.split("\n")).hasSize(9);
        assertThat(table).contains("1x1=1", "9x9=81");
    }

    @Test
    void table_firstRow_isOneByOne() {
        assertThat(tool.table().split("\n")[0]).isEqualTo("1x1=1");
    }
}
