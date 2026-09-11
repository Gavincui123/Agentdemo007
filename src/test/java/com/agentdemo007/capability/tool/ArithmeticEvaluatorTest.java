package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolRecoverableException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 手写递归下降算术解析器测试（第四层·示例工具内核）。
 *
 * <p>验收（Phase 9）：数学表达式正确计算；注入指令被解析器拦截不执行（仅允许数字/运算符/括号/sum 关键字，
 * 禁用 eval/ScriptEngine，BigDecimal 高精度）。
 */
class ArithmeticEvaluatorTest {

    private final ArithmeticEvaluator evaluator = new ArithmeticEvaluator();

    @Test
    void evaluates_simpleAddition() {
        assertThat(evaluator.evaluate("1+2")).isEqualByComparingTo(BigDecimal.valueOf(3));
    }

    @Test
    void evaluates_operatorPrecedence() {
        // 1 + 2*3 = 7
        assertThat(evaluator.evaluate("1+2*3")).isEqualByComparingTo(BigDecimal.valueOf(7));
    }

    @Test
    void evaluates_parentheses() {
        // (1+2)*4 = 12
        assertThat(evaluator.evaluate("(1+2)*4")).isEqualByComparingTo(BigDecimal.valueOf(12));
    }

    @Test
    void evaluates_unaryMinus() {
        assertThat(evaluator.evaluate("-5+3")).isEqualByComparingTo(BigDecimal.valueOf(-2));
        assertThat(evaluator.evaluate("3*-2")).isEqualByComparingTo(BigDecimal.valueOf(-6));
    }

    @Test
    void evaluates_decimalDivision() {
        // 10/3 → 10 位小数，HALF_UP
        assertThat(evaluator.evaluate("10/3")).isEqualByComparingTo(new BigDecimal("3.3333333333"));
    }

    @Test
    void evaluates_sumRange() {
        // sum(1..100) = 5050
        assertThat(evaluator.evaluate("sum(1..100)")).isEqualByComparingTo(BigDecimal.valueOf(5050));
    }

    @Test
    void evaluates_sumRange_single() {
        assertThat(evaluator.evaluate("sum(5..5)")).isEqualByComparingTo(BigDecimal.valueOf(5));
    }

    @Test
    void evaluates_sumRange_reversed_yieldsZero() {
        // lo > hi → 空区间求和 = 0
        assertThat(evaluator.evaluate("sum(5..3)")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void evaluates_nestedExpressionWithSum() {
        // 2*(1 + sum(1..10)) = 2*(1+55) = 112
        assertThat(evaluator.evaluate("2*(1+sum(1..10))")).isEqualByComparingTo(BigDecimal.valueOf(112));
    }

    @Test
    void evaluates_withWhitespace() {
        assertThat(evaluator.evaluate("  1  +  2 ")).isEqualByComparingTo(BigDecimal.valueOf(3));
    }

    @Test
    void divideByZero_throwsToolRecoverable() {
        assertThatThrownBy(() -> evaluator.evaluate("1/0"))
                .isInstanceOf(ToolRecoverableException.class)
                .hasMessageContaining("除零");
    }

    @Test
    void injection_systemExit_rejected() {
        // 字母非法 → 拦截（"System.exit(0)" 含非语法字符，零执行）
        assertThatThrownBy(() -> evaluator.evaluate("System.exit(0)"))
                .isInstanceOf(ToolRecoverableException.class);
    }

    @Test
    void injection_letters_rejected() {
        assertThatThrownBy(() -> evaluator.evaluate("abc"))
                .isInstanceOf(ToolRecoverableException.class);
    }

    @Test
    void malformed_unbalancedParen_throws() {
        assertThatThrownBy(() -> evaluator.evaluate("(1+2"))
                .isInstanceOf(ToolRecoverableException.class);
    }

    @Test
    void trailingGarbage_throws() {
        assertThatThrownBy(() -> evaluator.evaluate("1+2abc"))
                .isInstanceOf(ToolRecoverableException.class);
    }

    @Test
    void empty_throws() {
        assertThatThrownBy(() -> evaluator.evaluate(""))
                .isInstanceOf(ToolRecoverableException.class);
    }

    @Test
    void evaluates_largeNumbersPrecise() {
        // 大数 + 区间求和精度验证
        assertThat(evaluator.evaluate("999999999999 + 1"))
                .isEqualByComparingTo(new BigDecimal("1000000000000"));
    }
}
