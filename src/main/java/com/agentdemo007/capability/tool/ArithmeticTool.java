package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolRecoverableException;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 算术工具（第四层·starter calc tool，② Slice 3 迁 LC4j {@link Tool}）。
 *
 * <p>对外暴露 LC4j {@link Tool}-annotated {@link #calculate(String)}，委托 {@link ArithmeticEvaluator}
 * 做表达式计算/区间求和。BigDecimal 高精度、无 eval、注入指令在词法阶段即被拒绝
 * （抛 {@link ToolRecoverableException}，LC4j {@link dev.langchain4j.service.tool.DefaultToolExecutor}
 * 原生吞 @Tool 异常→消息当结果返回，不透传 ToolExecutionStep，
 * [[langchain4j-boot4-compat-findings]]：自纠正开箱即用）。
 *
 * <p>退役映射：旧本地 {@code @Tool(name="arithmetic",...)} 注解 + {@link ArithmeticDetector} 关键词检测
 * 已退役——LC4j function-calling 由模型出 {@code tool_calls}（expression 参数），
 * DefaultToolExecutor 反射调本方法（plumbing 详见 {@link ToolCallExecutorTest}）。
 * spec name = 方法名 {@code "calculate"}（LC4j @Tool 无 name 属性，取方法名）。
 */
@Component
public class ArithmeticTool {

    private final ArithmeticEvaluator evaluator = new ArithmeticEvaluator();

    @Tool("四则运算与区间求和（BigDecimal 高精度，无 eval，注入拦截）")
    public String calculate(String expression) {
        return evaluator.evaluate(expression).toPlainString();
    }
}
