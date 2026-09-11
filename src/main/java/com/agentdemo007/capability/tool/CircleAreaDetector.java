package com.agentdemo007.capability.tool;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 圆形面积检测器（第四层·工具触发判定 + 参数抽取）。
 *
 * <p>输入含"圆"且能抽到 半径(数) 或 圆[形]面积 后紧跟数值 → 触发 {@link CircleAreaTool}。
 * 与 {@link ArithmeticDetector} 同范式：只判可确定性命中，自然语言无数值则不触发（交 LLM）。
 */
public class CircleAreaDetector {

    private static final Pattern RADIUS = Pattern.compile("半径\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern AFTER_CIRCLE = Pattern.compile("圆(?:形)?面积\\s*(\\d+(?:\\.\\d+)?)");

    public Optional<Map<String, Object>> detect(String input) {
        if (input == null || !input.contains("圆")) {
            return Optional.empty();
        }
        Matcher rm = RADIUS.matcher(input);
        if (rm.find()) {
            return Optional.of(Map.of("radius", Double.valueOf(rm.group(1))));
        }
        Matcher cm = AFTER_CIRCLE.matcher(input);
        if (cm.find()) {
            return Optional.of(Map.of("radius", Double.valueOf(cm.group(1))));
        }
        return Optional.empty();
    }
}
