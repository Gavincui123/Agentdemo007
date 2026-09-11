package com.agentdemo007.capability.tool;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 九九乘法表工具（第四层·starter calc tool，bootstrap 工具路径）。
 *
 * <p>打印 1x1 至 9x9 的下三角乘法表（每行 i 个式子，空格分隔，行间换行）。
 * 无参数：由 {@link MultiplicationTableDetector} 命中"乘法表/九九乘法/99乘法"即触发。
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
