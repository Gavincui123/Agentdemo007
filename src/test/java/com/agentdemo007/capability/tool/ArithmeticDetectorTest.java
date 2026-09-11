package com.agentdemo007.capability.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 算术工具检测器测试（第四层·工具调用触发判定）。
 *
 * <p>从用户输入判定是否触发算术工具并抽取表达式参数：剥离"计算/算一下/算"等前缀，
 * 剩余须为纯算术 token（数字/运算符/括号/小数点/sum 关键字/空白）且含数字。
 * 含字母（含 CJK）的注入或自然语言不触发工具（交由 LLM 正常对话）。
 */
class ArithmeticDetectorTest {

    private final ArithmeticDetector detector = new ArithmeticDetector();

    @Test
    void stripsComputePrefix() {
        assertThat(detector.detect("计算 1+2*3"))
                .hasValue(Map.of("expression", "1+2*3"));
        assertThat(detector.detect("算一下 sum(1..100)"))
                .hasValue(Map.of("expression", "sum(1..100)"));
        assertThat(detector.detect("算 3*5")).hasValue(Map.of("expression", "3*5"));
    }

    @Test
    void pureExpression_matched() {
        assertThat(detector.detect("(1+2)*4")).hasValue(Map.of("expression", "(1+2)*4"));
        assertThat(detector.detect("10/3")).hasValue(Map.of("expression", "10/3"));
    }

    @Test
    void divisionByZero_stillDetected_executorWillFail() {
        // 检测器只判 token 合法性；除零在执行期由解析器拒绝
        assertThat(detector.detect("1/0")).hasValue(Map.of("expression", "1/0"));
    }

    @Test
    void letters_rejected() {
        assertThat(detector.detect("你好")).isEmpty();
        assertThat(detector.detect("分析Q3")).isEmpty();
        assertThat(detector.detect("System.exit(0)")).isEmpty();
        assertThat(detector.detect("1+2abc")).isEmpty();
    }

    @Test
    void onlyOperators_noDigits_rejected() {
        assertThat(detector.detect("+-*/")).isEmpty();
        assertThat(detector.detect("()")).isEmpty();
    }

    @Test
    void empty_rejected() {
        assertThat(detector.detect("")).isEmpty();
        assertThat(detector.detect("   ")).isEmpty();
        assertThat(detector.detect(null)).isEmpty();
    }
}
