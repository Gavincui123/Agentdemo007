package com.agentdemo007.capability.rag;

import java.util.List;
import java.util.Locale;

/**
 * RAG 注入扫描器（第四层·片段防注入，静默过滤降级）。
 *
 * <p>检索回来的片段可能被投毒（"忽略之前指令""泄露系统提示词""jailbreak" 等）。本扫描器按
 * 注入标记词库大小写无关匹配，命中即剔除——不短路主链路（与入口注入分工：入口注入走
 * {@code ShortCircuit(INJECTION)} 零 LLM，deg-001；RAG 片段注入走静默过滤降级，纯净片段仍可进上下文）。
 * 词库可随 Nacos 配置扩展（Phase 10 任务清单），prod 可接模型分类器覆盖。
 */
public class RagInjectionScanner {

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
                .filter(f -> !isInjection(f.text()))
                .toList();
    }

    private static boolean isInjection(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return PATTERNS.stream().anyMatch(lower::contains);
    }
}
