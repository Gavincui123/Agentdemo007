package com.agentdemo007.capability.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 LC4j {@link DefaultToolExecutor} 原生完成「解析 JSON 参数 → 强转类型 → 反射调 @Tool 方法」，
 * 项目不再手撸反射派发器（[[dont-hardwrite-use-dep-methods]]：库方法优先）。
 *
 * <p>这是 option B 退役后的 function-calling 原语验证——工具执行交给 LC4j，
 * 项目只在其上装饰韧性（熔断/audit/降级，见后续切片）。
 */
class Lc4jToolExecutionTest {

    @Test
    void defaultToolExecutor_parsesArgsAndInvokesTool_noHandRolledReflection() throws Exception {
        TriangleAreaTool tool = new TriangleAreaTool();
        Method triangleArea = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);

        // LC4j 执行器：bean + @Tool Method → 内部 prepareArguments + coerceArgument（JSON→double→反射）
        DefaultToolExecutor executor = new DefaultToolExecutor(tool, triangleArea);

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .name("triangleArea")
                .arguments("{\"base\":3,\"height\":4}")
                .build();

        String result = executor.execute(req, null);

        assertThat(result).isEqualTo("6"); // 3 × 4 ÷ 2
    }
}
