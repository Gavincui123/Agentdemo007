package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolCircuitBreaker;
import com.agentdemo007.resilience.ToolCircuitOpenException;
import com.agentdemo007.resilience.ToolErrorFeedback;
import com.agentdemo007.resilience.ToolFeedbackOutcome;
import com.agentdemo007.resilience.ToolRecoverableException;

import java.util.Optional;

/**
 * 工具执行入口（第四层·串联 解析→校验→执行→自纠正）。
 *
 * <p>链路：{@link ParamParser} 解析工具调用 → {@link SchemaValidator} 校验参数 →
 * {@link ToolDefinition#executor()} 执行 → 返回结果文本。工具异常经 {@link ToolErrorFeedback}
 * 产出反馈 prompt，由 {@link Reparser} seam 重解析为修正调用并重试（受最大迭代限制，§5.8）。
 *
 * <p>三态：
 * <ul>
 *   <li>无工具调用（detector 未命中）→ {@link Optional#empty()}（正常对话，不触发工具）；</li>
 *   <li>执行成功 → {@link Optional#of}(结果)；</li>
 *   <li>自纠正耗尽（{@link ToolFeedbackOutcome.Exhausted} 或 {@link Reparser#NONE} 无法修正）→
 *       抛 {@link ToolRecoverableException}，由 {@code ToolExecutionStep} 收口为 {@code TOOL_FAILURE} 话术短路。</li>
 *   <li>熔断中（Phase 17 per-tool 断路器 OPEN）→ 抛 {@link ToolCircuitOpenException}，
 *       由 {@code ToolExecutionStep} 收口为 {@code TOOL_FAILURE} 话术短路（零 LLM，不进自纠正循环）。</li>
 * </ul>
 *
 * <p>自纠正循环本体在此落地（有界迭代）；LLM 重解析为 {@link Reparser} seam，
 * prod 接 LangChain4j（延后），dev 用 {@link Reparser#NONE}（任何异常立即耗尽→TOOL_FAILURE）。
 */
public class ToolExecutor {

    private final ParamParser parser;
    private final SchemaValidator validator;
    private final ToolRegistry registry;
    private final ToolErrorFeedback feedback;
    private final Reparser reparser;
    private final ToolCircuitBreaker breaker;

    public ToolExecutor(ParamParser parser, SchemaValidator validator, ToolRegistry registry,
                        ToolErrorFeedback feedback, Reparser reparser) {
        this(parser, validator, registry, feedback, reparser, null);
    }

    /** Phase 17：注入 per-tool 断路器（null=不启用，保留既有行为，dev/旧测试兼容）。 */
    public ToolExecutor(ParamParser parser, SchemaValidator validator, ToolRegistry registry,
                        ToolErrorFeedback feedback, Reparser reparser, ToolCircuitBreaker breaker) {
        this.parser = parser;
        this.validator = validator;
        this.registry = registry;
        this.feedback = feedback;
        this.reparser = (reparser != null) ? reparser : Reparser.NONE;
        this.breaker = breaker;
    }

    /**
     * 执行工具调用（若输入触发工具）。
     *
     * @param input 用户输入/标准化 Query
     * @return 工具结果文本；无工具调用返回 empty
     * @throws ToolRecoverableException 自纠正耗尽
     */
    public Optional<String> execute(String input) {
        Optional<ToolCall> call = parser.parse(input);
        if (call.isEmpty()) {
            return Optional.empty();
        }
        String toolName = call.get().toolName();
        if (breaker != null && !breaker.allow(toolName)) {
            throw new ToolCircuitOpenException(toolName);
        }
        try {
            Optional<String> result = executeCall(call.get(), 0);
            if (breaker != null) {
                breaker.recordSuccess(toolName);
            }
            return result;
        } catch (ToolRecoverableException e) {
            if (breaker != null) {
                breaker.recordFailure(toolName);
            }
            throw e;
        }
    }

    private Optional<String> executeCall(ToolCall call, int iteration) {
        ToolDefinition tool = registry.get(call.toolName());
        if (tool == null) {
            throw new ToolRecoverableException("未知工具: " + call.toolName());
        }
        try {
            ValidationResult vr = validator.validate(call.args(), tool);
            if (!vr.ok()) {
                throw new ToolRecoverableException("参数校验失败: " + String.join("; ", vr.errors()));
            }
            return Optional.of(tool.executor().apply(call.args()));
        } catch (ToolRecoverableException e) {
            ToolFeedbackOutcome outcome = feedback.feedback(e, iteration);
            if (outcome instanceof ToolFeedbackOutcome.Exhausted) {
                throw e; // 迭代耗尽 → 交上层 TOOL_FAILURE 收口
            }
            // Resubmit：交 LLM 自纠正重解析
            String prompt = ((ToolFeedbackOutcome.Resubmit) outcome).prompt();
            Optional<ToolCall> fixed = reparser.reparse(prompt);
            if (fixed.isEmpty()) {
                throw e; // 无 LLM / 无法修正 → 立即耗尽
            }
            return executeCall(fixed.get(), iteration + 1);
        } catch (Exception e) {
            // 非预期异常统一包装为可恢复异常，纳入反馈/降级路径（②每步降级）
            throw new ToolRecoverableException("工具执行异常: " + e.getMessage(), e);
        }
    }
}
