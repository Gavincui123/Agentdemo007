package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolRecoverableException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 三角形面积工具（第四层·starter calc tool，bootstrap 工具路径）。
 *
 * <p>面积 = 底 × 高 ÷ 2（BigDecimal 高精度，与 {@link ArithmeticTool} 同精度范式）。
 * 由 {@link TriangleAreaDetector} 从用户输入判定触发并抽取 底/高，经 {@link SchemaValidator}
 * 校验后由 {@link ToolExecutor} 执行。参数非法（负值）抛 {@link ToolRecoverableException}
 * 走自纠正/耗尽短路（{@code TOOL_FAILURE}）。
 *
 * <p>详见 [[routeplan-design]] 缺口实现优先级①：starter calc tools 先做，后续真业务工具
 * （订单/物流/库存/价格）接上即用。
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
