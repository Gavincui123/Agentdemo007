package com.agentdemo007.session.model;

import java.util.Objects;

/**
 * 标准化 Query（第二层·会话理解层的数据收口载体）。
 *
 * <p>用户问题经 {@code QueryRewriter} 改写（结合历史上下文消解指代/省略/口语化）后，
 * 以强类型 {@link StandardQuery} 承载，写入 {@code PipelineContext.standardQuery}，
 * 供下游意图识别（Phase 7）消费——禁止各步用裸 {@code String} 私相传参（§5.14 统一收口）。
 *
 * <p>改写质量校验（{@code RewriteQualityChecker}）不达标时回退为 {@code rawInput} 包装的实例，
 * 保证下游始终拿到非空的自足 Query（②每步降级：不阻塞链路）。
 */
public record StandardQuery(String text) {

    public StandardQuery {
        Objects.requireNonNull(text, "标准化 Query 文本不能为空");
        if (text.isBlank()) {
            throw new IllegalArgumentException("标准化 Query 文本不能为空白");
        }
    }

    /** 便捷工厂。 */
    public static StandardQuery of(String text) {
        return new StandardQuery(text);
    }
}
