package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolErrorFeedback;
import com.agentdemo007.resilience.ToolRecoverableException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具执行器测试（第四层·解析→校验→执行→自纠正循环）。
 *
 * <p>验收（Phase 9）：工具异常 → 完整异常内容反馈 LLM，LLM 自纠正后成功执行；耗尽短路。
 * Reparser seam：prod=LLM（延后），test=stub 模拟 LLM 修正调用。
 */
class ToolExecutorTest {

    private final ToolRegistry registry = new ToolRegistry();
    private final ParamParser parser = new ParamParser(registry);
    private final SchemaValidator validator = new SchemaValidator();
    private final ToolErrorFeedback feedback = new ToolErrorFeedback(3); // maxIterations=3

    /** 构造工具：detector 总命中并把 input 作为 expression；executor 由 lambda 注入。 */
    private void registerTool(java.util.function.Function<Map<String, Object>, String> executor) {
        registry.register(new ToolDefinition(
                "arith", "算术",
                List.of(new ParamSpec("expression", String.class, true)),
                input -> Optional.of(Map.of("expression", input)),
                executor));
    }

    private ToolExecutor executorWith(Reparser reparser) {
        return new ToolExecutor(parser, validator, registry, feedback, reparser);
    }

    @Test
    void noTool_returnsEmpty() {
        ToolExecutor executor = new ToolExecutor(parser, validator, registry, feedback, Reparser.NONE);
        assertThat(executor.execute("hi")).isEmpty();
    }

    @Test
    void detectorMisses_returnsEmpty() {
        registry.register(new ToolDefinition(
                "arith", "算术", List.of(),
                input -> Optional.empty(),
                args -> "x"));
        assertThat(executorWith(Reparser.NONE).execute("hi")).isEmpty();
    }

    @Test
    void happyPath_returnsResult() {
        registerTool(args -> "R:" + args.get("expression"));
        assertThat(executorWith(Reparser.NONE).execute("1+2")).contains("R:1+2");
    }

    @Test
    void toolError_reparserSelfCorrects_returnsResult() {
        // executor 对 "1/0" 抛除零异常；reparser 把调用修正为 "1+1" 后成功
        registerTool(args -> {
            if ("1/0".equals(args.get("expression"))) {
                throw new ToolRecoverableException("除零错误");
            }
            return "R:" + args.get("expression");
        });
        Reparser reparser = prompt -> Optional.of(new ToolCall("arith", Map.of("expression", "1+1")));

        Optional<String> result = executorWith(reparser).execute("1/0");

        assertThat(result).contains("R:1+1");
    }

    @Test
    void validateFailure_reparserSelfCorrects_returnsResult() {
        // detector 返回空参数 → Schema 校验失败 → 反馈 → reparser 修正
        registry.register(new ToolDefinition(
                "arith", "算术",
                List.of(new ParamSpec("expression", String.class, true)),
                input -> Optional.of(Map.of()), // 故意不填 expression
                args -> "R:" + args.get("expression")));
        Reparser reparser = prompt -> Optional.of(new ToolCall("arith", Map.of("expression", "1+1")));

        Optional<String> result = executorWith(reparser).execute("any");

        assertThat(result).contains("R:1+1");
    }

    @Test
    void toolError_noReparser_throwsExhausted() {
        registerTool(args -> { throw new ToolRecoverableException("除零错误"); });
        assertThatThrownBy(() -> executorWith(Reparser.NONE).execute("1/0"))
                .isInstanceOf(ToolRecoverableException.class)
                .hasMessageContaining("除零");
    }

    @Test
    void toolError_reparserAlwaysBad_throwsAfterMaxIterations() {
        // executor 永远抛；reparser 永远返回仍会失败的调用 → 迭代至 maxIterations=3 耗尽
        registerTool(args -> { throw new ToolRecoverableException("bad"); });
        Reparser reparser = prompt -> Optional.of(new ToolCall("arith", Map.of("expression", "1+1")));

        assertThatThrownBy(() -> executorWith(reparser).execute("x"))
                .isInstanceOf(ToolRecoverableException.class);
    }
}
