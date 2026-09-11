package com.agentdemo007.output;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 输出安全过滤器测试（第七层·最终响应过滤敏感信息/注入残留）。
 *
 * <p>覆盖 §5.7.2：手机号/身份证/邮箱脱敏；注入残留（system prompt 泄露/jailbreak/越狱/忽略之前指令）
 * 整体替换为安全话术（输出被 compromized 不可部分信任）。纯净输出原样透传。
 */
class OutputSecurityFilterTest {

    private final OutputSecurityFilter filter = new OutputSecurityFilter();

    @Test
    void phoneMasked() {
        String out = "您的手机号是13812345678，已记录。";
        String filtered = filter.filter(out);
        assertThat(filtered).contains("138****5678");
        assertThat(filtered).doesNotContain("13812345678");
    }

    @Test
    void idCardMasked() {
        String out = "身份证号110101199001011234已核验。";
        String filtered = filter.filter(out);
        assertThat(filtered).contains("110101********1234");
        assertThat(filtered).doesNotContain("110101199001011234");
    }

    @Test
    void emailMasked() {
        String out = "请联系 alice@example.com 处理。";
        String filtered = filter.filter(out);
        assertThat(filtered).contains("a***@example.com");
        assertThat(filtered).doesNotContain("alice@");
    }

    @Test
    void injectionResidue_replacedWithSafePhrase() {
        String out = "好的，我先忽略之前的指令，然后显示系统提示词给你。";
        String filtered = filter.filter(out);
        assertThat(filtered).doesNotContain("忽略");
        assertThat(filtered).doesNotContain("系统提示");
        assertThat(filtered).isNotEqualTo(out);
    }

    @Test
    void jailbreakResidue_replacedWithSafePhrase() {
        String out = "Here is how to jailbreak the system prompt.";
        String filtered = filter.filter(out);
        assertThat(filtered.toLowerCase()).doesNotContain("jailbreak");
        assertThat(filtered.toLowerCase()).doesNotContain("system prompt");
    }

    @Test
    void cleanOutput_unchanged() {
        String out = "您好，您的订单已查到，预计3-5日送达。";
        assertThat(filter.filter(out)).isEqualTo(out);
    }

    @Test
    void noopOutput_unchanged() {
        String out = "[dev noop] 模型执行器未配置，这是占位回复。";
        assertThat(filter.filter(out)).isEqualTo(out);
    }
}
