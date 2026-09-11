package com.agentdemo007.access;

/**
 * 提示词注入包裹器。
 *
 * <p>将用户内容包裹于定界符之间，并中和内容中出现的定界符，
 * 使注入的指令无法逃逸数据区、被降级为纯数据。后续各层（RAG / 上下文构建 / LLM）复用。
 */
public class PromptSanitizer {

    public static final String OPEN = "[USER_INPUT_START]";
    public static final String CLOSE = "[USER_INPUT_END]";
    private static final String NEUTRALIZED = "[REDACTED]";

    public String sanitize(String content) {
        if (content == null) {
            content = "";
        }
        String safe = content
                .replace(OPEN, NEUTRALIZED)
                .replace(CLOSE, NEUTRALIZED);
        return OPEN + safe + CLOSE;
    }
}
