package com.agentdemo007.context;

import com.agentdemo007.intent.Intent;
import com.agentdemo007.prompt.PromptRegistry;
import com.agentdemo007.prompt.PromptTemplate;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分段式 system prompt 装配器测试（[[segmented-systemprompt-intent-design]] §6）。
 *
 * <p>用 lambda 桩 {@link PromptRegistry}（单方法接口）喂受控 YAML 模板，验证
 * scene 选取/sort 降序+并列稳定/零匹配②降级/解析失败②降级/enabled 跳过/{{var}} 渲染。
 */
class SystemPromptAssemblerTest {

    private static final String FALLBACK = "FALLBACK_DEFAULT";

    private static final String RICH_YAML = """
            - id: role
              prompt: "你是客服"
              scene: all
              sort: 100
              enabled: true
            - id: tone
              prompt: "语气友善"
              scene: all
              sort: 95
              enabled: true
            - id: chat
              prompt: "闲聊短回"
              scene: CHIT_CHAT
              sort: 75
              enabled: true
            - id: refund
              prompt: "退款指引"
              scene: refund_request
              sort: 60
              enabled: true
            - id: tie1
              prompt: "并列一"
              scene: all
              sort: 60
              enabled: true
            - id: tie2
              prompt: "并列二"
              scene: all
              sort: 60
              enabled: true
            - id: disabled
              prompt: "禁用段"
              scene: all
              sort: 90
              enabled: false
            """;

    /** 桩 registry：返回固定 YAML 模板。 */
    private static PromptRegistry registryWith(String yaml) {
        return (key, spec) -> Optional.of(
                new PromptTemplate(SystemPromptAssembler.SEGMENTS_PROMPT_KEY, "v1", yaml, "md5"));
    }

    private static SystemPromptAssembler assemblerWith(String yaml) {
        return new SystemPromptAssembler(registryWith(yaml), FALLBACK);
    }

    @Test
    void sceneAll_alwaysIncluded_evenWhenNoIntent() {
        // coarse=null fine=null → 只匹配 scene=all（闲聊 @150 短路后兜底场景）
        String out = assemblerWith(RICH_YAML).assemble(null, null, Map.of());
        assertThat(out).contains("你是客服").contains("语气友善");
        assertThat(out).doesNotContain("退款指引").doesNotContain("闲聊短回");
    }

    @Test
    void fineScene_matchesRefundSegment() {
        String out = assemblerWith(RICH_YAML).assemble(null, "refund_request", Map.of());
        assertThat(out).contains("你是客服").contains("退款指引"); // all + refund
        assertThat(out).doesNotContain("闲聊短回"); // CHIT_CHAT 不匹配
    }

    @Test
    void coarseScene_matchesChitChatSegment() {
        String out = assemblerWith(RICH_YAML).assemble(Intent.CHIT_CHAT, null, Map.of());
        assertThat(out).contains("你是客服").contains("闲聊短回"); // all + CHIT_CHAT
        assertThat(out).doesNotContain("退款指引");
    }

    @Test
    void sort_descendingWithStableTies() {
        String out = assemblerWith(RICH_YAML).assemble(null, null, Map.of());
        // sort 降序：role(100) → tone(95) → [tie1,tie2 都 60，配置序] → disabled(90)被跳过
        int role = out.indexOf("你是客服");
        int tone = out.indexOf("语气友善");
        int tie1 = out.indexOf("并列一");
        int tie2 = out.indexOf("并列二");
        assertThat(role).isLessThan(tone);
        assertThat(tone).isLessThan(tie1);
        assertThat(tie1).isLessThan(tie2); // 并列保配置序（稳定排序）
        assertThat(out).doesNotContain("禁用段");
    }

    @Test
    void zeroMatch_returnsFallback() {
        // 全片段 scene=refund_request，给 coarse=CHIT_CHAT fine=general_chat → 零匹配
        String yaml = """
                - id: refund
                  prompt: "退款指引"
                  scene: refund_request
                  sort: 60
                  enabled: true
                """;
        String out = assemblerWith(yaml).assemble(Intent.CHIT_CHAT, "general_chat", Map.of());
        assertThat(out).isEqualTo(FALLBACK);
    }

    @Test
    void registryMiss_returnsFallback() {
        PromptRegistry empty = (key, spec) -> Optional.empty();
        String out = new SystemPromptAssembler(empty, FALLBACK).assemble(null, null, Map.of());
        assertThat(out).isEqualTo(FALLBACK);
    }

    @Test
    void parseFailure_returnsFallback() {
        // 非合法 YAML → SnakeYAML 抛 → parse 返回空清单 → ②降级 fallback
        String out = assemblerWith("  - [ not : closed").assemble(null, null, Map.of());
        assertThat(out).isEqualTo(FALLBACK);
    }

    @Test
    void rootNotList_returnsFallback() {
        // 根为 Map（用户误把对象当数组）→ 非 List → 空清单 → fallback
        String out = assemblerWith("id: role\nprompt: 单条对象错配\n").assemble(null, null, Map.of());
        assertThat(out).isEqualTo(FALLBACK);
    }

    @Test
    void renderVar_substituted() {
        String yaml = """
                - id: greet
                  prompt: "你好 {{name}}，我是客服"
                  scene: all
                  sort: 1
                  enabled: true
                """;
        String out = assemblerWith(yaml).assemble(null, null, Map.of("name", "张三"));
        assertThat(out).contains("你好 张三，我是客服").doesNotContain("{{name}}");
    }

    @Test
    void renderVar_missing_replacedWithEmpty() {
        String yaml = """
                - id: greet
                  prompt: "你好{{name}}结束"
                  scene: all
                  sort: 1
                  enabled: true
                """;
        String out = assemblerWith(yaml).assemble(null, null, Map.of()); // 无 name 变量
        assertThat(out).contains("你好结束").doesNotContain("{{");
    }
}
