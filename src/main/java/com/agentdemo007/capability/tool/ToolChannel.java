package com.agentdemo007.capability.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具通道标记（[[business-tools-workflow-dag]] §2.2）：标在 {@code @dev.langchain4j.agent.tool.Tool} 方法上
 * 声明其结果归属通道。
 *
 * <p>{@link ToolSchemaProvider} 反射此注解入 {@code ToolBinding.category}，
 * {@link ToolCallExecutor} 据此为每个 {@link ToolCallResult} 标 category，
 * {@link ToolExecutionStep} 按 category 路由（RUNTIME/RAG/COMPUTE 三通道）。
 * 缺省（无注解）= {@link ToolCategory#COMPUTE}（向后兼容既有计算工具）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolChannel {
    ToolCategory value();
}
