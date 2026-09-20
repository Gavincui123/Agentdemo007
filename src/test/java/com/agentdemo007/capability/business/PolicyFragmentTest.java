package com.agentdemo007.capability.business;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 政策片段 carrier 单测（[[refusal-design]] hit 标志扩展）：
 * hit 标志随 JSON 往返、旧格式（无 hit）向后兼容按命中、畸形 JSON 降级 empty。
 */
class PolicyFragmentTest {

    @Test
    void twoArgConstructorDefaultsToHit() {
        assertThat(new PolicyFragment("7天无理由退货", "退货政策知识库§3").hit()).isTrue();
    }

    @Test
    void jsonRoundTripKeepsHitFlag() {
        String miss = new PolicyFragment("暂无相关政策信息", "兜底政策", false).toJson();
        Optional<PolicyFragment> parsed = PolicyFragment.fromJson(miss);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().hit()).isFalse();
        assertThat(parsed.get().text()).isEqualTo("暂无相关政策信息");

        String hit = new PolicyFragment("退款3-7个工作日", "退款政策知识库§2", true).toJson();
        assertThat(PolicyFragment.fromJson(hit)).isPresent()
                .get().satisfies(f -> assertThat(((PolicyFragment) f).hit()).isTrue());
    }

    @Test
    void legacyJsonWithoutHitTreatedAsHit() {
        Optional<PolicyFragment> parsed = PolicyFragment.fromJson(
                "{\"text\":\"旧格式政策\",\"source\":\"退货政策知识库§1\"}");
        assertThat(parsed).isPresent();
        assertThat(parsed.get().hit()).isTrue(); // 旧格式不因新字段误判为未命中
    }

    @Test
    void malformedOrBlankJsonFallsBackToEmpty() {
        assertThat(PolicyFragment.fromJson(null)).isEmpty();
        assertThat(PolicyFragment.fromJson("  ")).isEmpty();
        assertThat(PolicyFragment.fromJson("不是JSON")).isEmpty();
        assertThat(PolicyFragment.fromJson("{\"source\":\"缺text\"}")).isEmpty();
    }
}
