package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolRecoverableException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 圆形面积工具（第四层·starter calc tool，LC4j {@link dev.langchain4j.agent.tool.Tool}）。
 *
 * <p>面积 = π × 半径²（BigDecimal 高精度，保留 4 位小数）。LC4j function-calling：模型出
 * {@code tool_calls}（radius 参数）→ {@link dev.langchain4j.service.tool.DefaultToolExecutor}
 * 反射调本方法（plumbing 详见 {@link ToolCallExecutorTest}）。负半径抛
 * {@link ToolRecoverableException}（DefaultToolExecutor 原生吞→消息当结果返回，不透传，
 * [[langchain4j-boot4-compat-findings]]）。
 *
 * <p>退役映射：旧 {@code CircleAreaDetector} 关键词检测 + {@code SchemaValidator} 校验 +
 * 手撸 {@code ToolExecutor} 执行已退役——LC4j 原生取 schema/参数强转/反射。
 */
@Component
public class CircleAreaTool {

    private static final BigDecimal PI = new BigDecimal("3.141592653589793");

    @Tool("圆形面积 = π × 半径²")
    public String circleArea(@P("半径") double radius) {
        if (radius < 0) {
            throw new ToolRecoverableException("半径不可为负");
        }
        BigDecimal r = BigDecimal.valueOf(radius);
        BigDecimal area = r.multiply(r).multiply(PI)
                .setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return area.toPlainString();
    }
}
