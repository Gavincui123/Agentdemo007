package com.agentdemo007.capability.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具调用参数解析器测试（第四层·从用户输入解析工具调用）。
 *
 * <p>验收（Phase 9）：从用户输入/LLM 输出解析工具调用参数。ParamParser 遍历注册表，
 * 每个工具的 detector 决定是否命中并抽取参数；首个命中胜出，无命中返回 empty。
 * 本测试用 lambda detector 验证通用解析逻辑（算术专用检测器见 ArithmeticDetectorTest）。
 */
class ParamParserTest {

    private final ToolRegistry registry = new ToolRegistry();
    private final ParamParser parser = new ParamParser(registry);

    private static ToolDefinition tool(String name, java.util.function.Function<String, Optional<Map<String, Object>>> detector) {
        return new ToolDefinition(
                name, name, List.of(),
                detector,
                args -> "r:" + args);
    }

    @Test
    void firstMatchingTool_wins() {
        registry.register(tool("a", input -> input.startsWith("a") ? Optional.of(Map.of("v", input)) : Optional.empty()));
        registry.register(tool("b", input -> input.startsWith("b") ? Optional.of(Map.of("v", input)) : Optional.empty()));

        Optional<ToolCall> call = parser.parse("apple");
        assertThat(call).isPresent();
        assertThat(call.get().toolName()).isEqualTo("a");
        assertThat(call.get().args()).containsEntry("v", "apple");
    }

    @Test
    void secondTool_matchesWhenFirstMisses() {
        registry.register(tool("a", input -> Optional.empty()));
        registry.register(tool("b", input -> Optional.of(Map.of("v", input))));

        Optional<ToolCall> call = parser.parse("hi");
        assertThat(call).isPresent();
        assertThat(call.get().toolName()).isEqualTo("b");
    }

    @Test
    void noMatch_returnsEmpty() {
        registry.register(tool("a", input -> Optional.empty()));
        assertThat(parser.parse("anything")).isEmpty();
    }

    @Test
    void emptyRegistry_returnsEmpty() {
        assertThat(parser.parse("anything")).isEmpty();
    }

    @Test
    void nullInput_returnsEmpty() {
        registry.register(tool("a", input -> Optional.of(Map.of("v", "x"))));
        assertThat(parser.parse(null)).isEmpty();
    }
}
