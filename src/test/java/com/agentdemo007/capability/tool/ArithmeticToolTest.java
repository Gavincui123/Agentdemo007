package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolRecoverableException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 算术工具单测（② Slice 3 迁 LC4j @Tool 后·直接测 {@code calculate} 方法业务逻辑）。
 *
 * <p>退役映射：旧 {@code toolAnnotation_presentWithCorrectMetadata} 测本地 @Tool 元数据（name/description），
 * 已退役——LC4j @Tool 无 name 属性（spec name = 方法名 "calculate"），@Tool 拾起 + spec 名由
 * {@link ToolSchemaProviderTest}（反射单测）+ {@link ToolCircuitWiringTest}（Spring 接线烟测）验。
 * 本测聚焦 calculate 自身契约：表达式正确计算 + 注入指令词法阶段拦截 + 除零拒绝
 * （DefaultToolExecutor 原生吞 @Tool 异常→消息当结果返回，不透传 ToolExecutionStep，
 * [[langchain4j-boot4-compat-findings]]）。
 */
class ArithmeticToolTest {

    private final ArithmeticTool tool = new ArithmeticTool();

    @Test
    void calculate_addition() {
        assertThat(tool.calculate("1+2")).isEqualTo("3");
    }

    @Test
    void calculate_precedence() {
        assertThat(tool.calculate("1+2*3")).isEqualTo("7");
    }

    @Test
    void calculate_parens() {
        assertThat(tool.calculate("(1+2)*4")).isEqualTo("12");
    }

    @Test
    void calculate_negative() {
        assertThat(tool.calculate("-5+3")).isEqualTo("-2");
    }

    @Test
    void calculate_decimalDivision() {
        assertThat(tool.calculate("10/3")).isEqualTo("3.3333333333");
    }

    @Test
    void calculate_sumRange() {
        assertThat(tool.calculate("sum(1..100)")).isEqualTo("5050");
    }

    @Test
    void calculate_largeNumber() {
        assertThat(tool.calculate("999999999999 + 1")).isEqualTo("1000000000000");
    }

    @Test
    void injection_systemExit_rejected() {
        assertThatThrownBy(() -> tool.calculate("System.exit(0)"))
                .isInstanceOf(ToolRecoverableException.class);
    }

    @Test
    void divideByZero_rejected() {
        assertThatThrownBy(() -> tool.calculate("1/0"))
                .isInstanceOf(ToolRecoverableException.class)
                .hasMessageContaining("除零");
    }
}
