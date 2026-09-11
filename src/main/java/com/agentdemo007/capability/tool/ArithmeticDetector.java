package com.agentdemo007.capability.tool;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 算术工具检测器（第四层·工具调用触发判定）。
 *
 * <p>从用户输入判定是否触发 {@code ArithmeticTool} 并抽取 {@code expression} 参数：
 * <ol>
 *   <li>剥离前缀 {@code 计算/算一下/帮我算/帮我算一下/算}（最长前缀优先）；</li>
 *   <li>剩余须为纯算术 token（数字、{@code + - * / ( ) .}、空白、{@code sum} 关键字）且至少含一个数字；</li>
 *   <li>含字母（含 CJK，如 {@code "System.exit(0)"}、{@code "你好"}）的自然语言不触发工具，交 LLM 正常对话。</li>
 * </ol>
 *
 * <p>本检测器只判 token 合法性；语义错误（如除零、括号不配）在 {@link ArithmeticEvaluator} 执行期拒绝，
 * 经 {@code ToolErrorFeedback} 反馈自纠正或耗尽短路 {@code TOOL_FAILURE}。
 */
public class ArithmeticDetector {

    /** 前缀按长度降序，保证最长前缀优先匹配（"帮我算一下" 先于 "算"）。 */
    private static final List<String> PREFIXES = List.of(
            "帮我算一下", "帮我算", "算一下", "计算", "算");

    private static final String ALLOWED = "+-*/().";

    public Optional<Map<String, Object>> detect(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String stripped = stripPrefix(input.trim());
        if (stripped.isBlank()) {
            return Optional.empty();
        }
        // 移除 sum 关键字后，剩余不得含字母，且须含数字，其它字符仅限运算符集合
        String noSum = stripped.replaceAll("(?i)sum", "");
        if (noSum.chars().noneMatch(Character::isDigit)) {
            return Optional.empty();
        }
        if (noSum.chars().anyMatch(c -> !isAllowed((char) c))) {
            return Optional.empty();
        }
        return Optional.of(Map.of("expression", stripped));
    }

    private static String stripPrefix(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        for (String prefix : PREFIXES) {
            if (lower.startsWith(prefix)) {
                return input.substring(prefix.length()).trim();
            }
        }
        return input.trim();
    }

    private static boolean isAllowed(char c) {
        return Character.isDigit(c) || Character.isWhitespace(c) || ALLOWED.indexOf(c) >= 0;
    }
}
