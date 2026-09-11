package com.agentdemo007.capability.tool;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 工具调用参数解析器（第四层·从用户输入/LLM 输出解析工具调用参数）。
 *
 * <p>遍历 {@link ToolRegistry#all()}，对每个工具调用其 {@code detector}；首个命中者胜出，
 * 产出 {@link ToolCall}（工具名 + 参数）。无命中返回 {@link Optional#empty()}（无工具调用，正常对话）。
 *
 * <p>命中判定与参数抽取由各工具 {@code detector} 自治——算术工具见 {@link ArithmeticDetector}；
 * 未来 function-calling 模式由 LLM 输出解析器实现（随 LangChain4j 接入）。
 */
@Component
public class ParamParser {

    private final ToolRegistry registry;

    public ParamParser(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param input 用户输入/标准化 Query（可为 null）
     * @return 命中的工具调用，无则 empty
     */
    public Optional<ToolCall> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        for (ToolDefinition tool : registry.all()) {
            if (tool.detector() == null) {
                continue;
            }
            Optional<Map<String, Object>> args = tool.detector().apply(input);
            if (args != null && args.isPresent()) {
                return Optional.of(new ToolCall(tool.name(), args.get()));
            }
        }
        return Optional.empty();
    }
}
