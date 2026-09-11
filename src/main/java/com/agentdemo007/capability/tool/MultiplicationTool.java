package com.agentdemo007.capability.tool;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 九九乘法表工具（第四层·starter calc tool，LC4j {@link dev.langchain4j.agent.tool.Tool}）。
 *
 * <p>打印 1x1 至 9x9 的下三角乘法表（每行 i 个式子，空格分隔，行间换行）。无参数：
 * LC4j function-calling 模型出无参 {@code tool_calls} → {@link dev.langchain4j.service.tool.DefaultToolExecutor}
 * 反射调本方法（plumbing 详见 {@link ToolCallExecutorTest}）。
 *
 * <p>退役映射：旧 {@code MultiplicationTableDetector} 关键词检测已退役——LC4j 原生取 schema/反射。
 */
@Component
public class MultiplicationTool {

    @Tool("打印九九乘法表")
    public String table() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 9; i++) {
            for (int j = 1; j <= i; j++) {
                if (j > 1) {
                    sb.append(' ');
                }
                sb.append(j).append('x').append(i).append('=').append(j * i);
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
