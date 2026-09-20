package com.agentdemo007.capability.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * RAG 注入扫描器（第四层·片段防注入，静默过滤降级）。
 *
 * <p>检索回来的片段可能被投毒（"忽略之前指令""泄露系统提示词""jailbreak" 等）。本扫描器按
 * 注入标记词库大小写无关匹配，命中即剔除——不短路主链路（与入口注入分工：入口注入走
 * {@code ShortCircuit(INJECTION)} 零 LLM，deg-001；RAG 片段注入走静默过滤降级，纯净片段仍可进上下文）。
 * 词库可随 Nacos 配置扩展（Phase 10 任务清单），prod 可接模型分类器覆盖。
 *
 * <p>观测：剔除是安全相关事件，逐条 INFO 留痕（source + 命中词库条目 + 片段摘要）——
 * 红队演示（corpus-redteam/91 类毒片）据此可在日志直接看到「毒片被召回且被拦」。
 */
public class RagInjectionScanner {

    private static final Logger log = LoggerFactory.getLogger(RagInjectionScanner.class);

    /** 注入标记词库（小写匹配，含中英文常见 prompt-injection 触发短语）。 */
    private static final List<String> PATTERNS = List.of(
            "ignore previous instructions",
            "ignore all previous",
            "ignore prior",
            "disregard previous",
            "disregard all",
            "忽略之前",
            "忽略上述",
            "忽略上面",
            "忽略所有",
            "reveal the system prompt",
            "reveal system prompt",
            "show the system prompt",
            "泄露系统",
            "显示系统提示",
            "提示词泄露",
            "system prompt",
            "jailbreak",
            "越狱");

    /**
     * 扫描并剔除注入片段。
     *
     * @param fragments 待扫描片段
     * @return 仅含纯净片段（保持原顺序）
     */
    public List<RagFragment> scan(List<RagFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }
        return fragments.stream()
                .filter(f -> {
                    String hit = matchPattern(f == null ? null : f.text());
                    if (hit != null) {
                        log.info("注入扫描剔除：source={} 命中词库='{}' 片段='{}…'",
                                f.source(), hit, abbreviate(f.text()));
                    }
                    return hit == null;
                })
                .toList();
    }

    /** 命中的注入词库条目（无命中返回 null）——剔除日志需要具体原因而非布尔。 */
    private static String matchPattern(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return PATTERNS.stream().filter(lower::contains).findFirst().orElse(null);
    }

    private static String abbreviate(String s) {
        return (s == null || s.length() <= 60) ? s : s.substring(0, 60);
    }
}
