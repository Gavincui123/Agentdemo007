package com.agentdemo007.capability.tool;

import java.util.Map;
import java.util.Optional;

/**
 * 九九乘法表检测器（第四层·工具触发判定，无参数抽取）。
 *
 * <p>输入含"乘法表"/"九九乘法"/"99乘法" → 触发 {@link MultiplicationTool}（无参数，返回空 args）。
 * 与 {@link ArithmeticDetector} 同范式：只判可确定性命中。
 */
public class MultiplicationTableDetector {

    public Optional<Map<String, Object>> detect(String input) {
        if (input == null) {
            return Optional.empty();
        }
        if (input.contains("乘法表") || input.contains("九九乘法") || input.contains("99乘法")) {
            return Optional.of(Map.of());
        }
        return Optional.empty();
    }
}
