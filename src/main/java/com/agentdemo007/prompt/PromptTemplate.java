package com.agentdemo007.prompt;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板（收口 DTO，Nacos SDK 类型不外泄到网关）。
 *
 * <p>携带 promptKey / 版本 / 模板内容 / md5。{@link #render} 用 {@code {{var}}} 替换变量——
 * dev 与 prod（{@code NacosPromptSource}）共用此类型与渲染逻辑，保证一致；
 * 我们持有 prompt 创作权，模板统一用 {@code {{var}}} 占位（与 Nacos UI 预览渲染解耦，运行时由本类负责）。
 */
public record PromptTemplate(String promptKey, String version, String template, String md5) {

    private static final Pattern VAR = Pattern.compile("\\{\\{(\\w+)}}");

    /** 用 vars 替换 {@code {{var}}}；缺失变量替换为空串。 */
    public String render(Map<String, String> vars) {
        if (vars == null || vars.isEmpty()) {
            return template;
        }
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = vars.getOrDefault(m.group(1), "");
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
