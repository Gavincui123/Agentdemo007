package com.agentdemo007.output;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出安全过滤器（第七层·最终响应过滤敏感信息/注入残留，§5.7.2）。
 *
 * <p>对称于接入层注入扫描与 RAG 注入扫描，但面向最终响应：
 * <ul>
 *   <li>敏感信息脱敏：手机号（前3后4，中间*）、身份证号（前6后4，中间*）、邮箱（本地首字符+***）；</li>
 *   <li>注入残留（system prompt 泄露 / jailbreak / 越狱 / 忽略之前指令 等）→ 整体替换为安全话术
 *       （输出被 compromized 不可部分信任，不逐词剔除以免残片误导）。</li>
 * </ul>
 * 纯净输出原样透传。pattern 库可经 Nacos 热更新扩展。
 */
public class OutputSecurityFilter {

    /** 注入残留命中后的安全话术（输出 compromized，整体替换）。 */
    private static final String SAFE_PHRASE = "[该回复因安全策略已过滤，如需帮助请联系人工客服。]";

    private static final List<String> INJECTION_PATTERNS = List.of(
            "ignore previous instructions", "ignore all previous", "ignore prior",
            "disregard previous", "disregard all",
            "reveal the system prompt", "reveal system prompt",
            "show the system prompt", "system prompt",
            "jailbreak", "越狱",
            "忽略之前", "忽略上述", "忽略上面", "忽略所有",
            "泄露系统", "显示系统提示", "提示词泄露"
    );

    // 11 位手机号（1 开头，前后非数字）
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1\\d{10}(?!\\d)");
    // 18 位身份证（17 位数字 + 末位数字/X/x，前后非数字）
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    // 邮箱
    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    public String filter(String output) {
        if (output == null || output.isBlank()) {
            return output;
        }
        if (containsInjectionResidue(output)) {
            return SAFE_PHRASE;
        }
        String masked = maskIdCard(output);
        masked = maskPhone(masked);
        masked = maskEmail(masked);
        return masked;
    }

    private boolean containsInjectionResidue(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return INJECTION_PATTERNS.stream().anyMatch(p -> lower.contains(p.toLowerCase(Locale.ROOT)));
    }

    private String maskPhone(String text) {
        Matcher m = PHONE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String g = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(g.substring(0, 3) + "****" + g.substring(7)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String maskIdCard(String text) {
        Matcher m = ID_CARD.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String g = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(g.substring(0, 6) + "********" + g.substring(14)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String maskEmail(String text) {
        Matcher m = EMAIL.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String g = m.group();
            int at = g.indexOf('@');
            String local = g.substring(0, at);
            String domain = g.substring(at);
            String maskedLocal = (local.isEmpty() ? "" : local.charAt(0)) + "***";
            m.appendReplacement(sb, Matcher.quoteReplacement(maskedLocal + domain));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
