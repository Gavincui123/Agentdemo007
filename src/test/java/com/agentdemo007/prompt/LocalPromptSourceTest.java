package com.agentdemo007.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地提示词源测试（dev：内存固定模板，喂 eval 黄金样例确定性）。
 *
 * <p>驱动收口类型 {@link PromptTemplate}（含 {@code render}）、{@link VersionSpec}、
 * {@link PromptRegistry} 接口、{@link LocalPromptSource}（dev 实现）。
 * prod 的 {@code NacosPromptSource}（typed SDK）由后续 NacosPromptSourceTest 覆盖。
 */
class LocalPromptSourceTest {

    private final LocalPromptSource source = new LocalPromptSource();

    @Test
    void getRegistered_returnsTemplate() {
        PromptTemplate t = new PromptTemplate("greet", "1.0", "你好 {{name}}", "md5-greet");
        source.put("greet", t);
        assertThat(source.get("greet", VersionSpec.latest())).contains(t);
    }

    @Test
    void getUnknown_returnsEmpty() {
        assertThat(source.get("nope", VersionSpec.latest())).isEmpty();
    }

    @Test
    void templateRendersVariables() {
        PromptTemplate t = new PromptTemplate("greet", "1.0", "你好 {{name}}，共 {{n}} 条", "md5");
        assertThat(t.render(Map.of("name", "张三", "n", "3"))).isEqualTo("你好 张三，共 3 条");
    }

    @Test
    void templateRender_missingVarBecomesEmpty() {
        PromptTemplate t = new PromptTemplate("x", "1.0", "{{a}}-{{b}}", "md5");
        assertThat(t.render(Map.of("a", "X"))).isEqualTo("X-");
    }
}
