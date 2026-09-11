package com.agentdemo007.session.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标准化 Query 测试（第二层·会话理解层的数据收口载体）。
 *
 * <p>改写后的自足问题以强类型 {@link StandardQuery} 承载，写入 {@code PipelineContext.standardQuery}，
 * 供下游意图识别（Phase 7）消费——禁止各步用裸 {@code String} 私相传参（§5.14）。
 */
class StandardQueryTest {

    @Test
    void of_preservesText() {
        StandardQuery q = StandardQuery.of("Q3 销售额是多少");
        assertThat(q.text()).isEqualTo("Q3 销售额是多少");
    }

    @Test
    void of_blank_throws() {
        assertThatThrownBy(() -> StandardQuery.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_null_throws() {
        assertThatThrownBy(() -> StandardQuery.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalsByValue() {
        assertThat(StandardQuery.of("x")).isEqualTo(StandardQuery.of("x"));
    }
}
