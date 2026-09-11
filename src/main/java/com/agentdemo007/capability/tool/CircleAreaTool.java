package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolRecoverableException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 圆形面积工具（第四层·starter calc tool，bootstrap 工具路径）。
 *
 * <p>面积 = π × 半径²（BigDecimal 高精度，保留 4 位小数）。与 {@link ArithmeticTool} 同范式：
 * {@link CircleAreaDetector} 抽取半径，{@link SchemaValidator} 校验，{@link ToolExecutor} 执行。
 * 负半径抛 {@link ToolRecoverableException} 走自纠正/耗尽短路。
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
