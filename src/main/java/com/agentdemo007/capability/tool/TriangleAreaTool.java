package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolRecoverableException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 三角形面积工具（第四层·starter calc tool，LC4j {@link dev.langchain4j.agent.tool.Tool}）。
 *
 * <p>面积 = 底 × 高 ÷ 2（BigDecimal 高精度）。LC4j function-calling：模型出 {@code tool_calls}
 * （base/height 参数）→ {@link dev.langchain4j.service.tool.DefaultToolExecutor} 反射调本方法
 * （plumbing 详见 {@link ToolCallExecutorTest}）。参数非法（负值）抛 {@link ToolRecoverableException}
 * （DefaultToolExecutor 原生吞 @Tool 异常→消息当结果返回，不透传 ToolExecutionStep，
 * [[langchain4j-boot4-compat-findings]]：自纠正开箱即用）。
 *
 * <p>退役映射：旧 {@code TriangleAreaDetector} 关键词检测 + {@code SchemaValidator} 校验 +
 * 手撸 {@code ToolExecutor} 执行已退役——LC4j 原生取 schema/参数强转/反射（无关键词检测/手校验）。
 * 详见 [[routeplan-design]] 缺口实现优先级①：starter calc tools 先做，后续真业务工具接上即用。
 */
@Component
public class TriangleAreaTool {

    private static final BigDecimal HALF = new BigDecimal("2");

    @Tool("三角形面积 = 底 × 高 ÷ 2")
    public String triangleArea(@P("底") double base, @P("高") double height) {
        if (base < 0 || height < 0) {
            throw new ToolRecoverableException("底/高不可为负");
        }
        BigDecimal area = BigDecimal.valueOf(base)
                .multiply(BigDecimal.valueOf(height))
                .divide(HALF, 10, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return area.toPlainString();
    }
}
