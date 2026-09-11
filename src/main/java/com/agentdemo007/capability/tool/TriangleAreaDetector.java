package com.agentdemo007.capability.tool;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 三角形面积检测器（第四层·工具触发判定 + 参数抽取）。
 *
 * <p>输入含"三角形"且能抽到 底(数) + 高(数) → 触发 {@link TriangleAreaTool}。
 * 与 {@link ArithmeticDetector} 同范式：只判可确定性命中——自然语言无 底/高 数值则不触发（交 LLM）。
 *
 * <ul>
 *   <li>底 支持 底/底边/底线 前缀，后接数值（含小数）；</li>
 *   <li>高 支持 高 前缀，后接数值（含小数）；</li>
 *   <li>两者都抽到方触发，缺一则不命中（避免半截触发）。</li>
 * </ul>
 */
public class TriangleAreaDetector {

    private static final Pattern BASE = Pattern.compile("底[边线]?\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern HEIGHT = Pattern.compile("高\\s*(\\d+(?:\\.\\d+)?)");

    public Optional<Map<String, Object>> detect(String input) {
        if (input == null || !input.contains("三角形")) {
            return Optional.empty();
        }
        Matcher bm = BASE.matcher(input);
        Matcher hm = HEIGHT.matcher(input);
        if (!bm.find() || !hm.find()) {
            return Optional.empty();
        }
        return Optional.of(Map.of(
                "base", Double.valueOf(bm.group(1)),
                "height", Double.valueOf(hm.group(1))));
    }
}
