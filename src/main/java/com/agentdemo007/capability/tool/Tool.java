package com.agentdemo007.capability.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具声明注解（第四层·示例工具元数据）。
 *
 * <p>标注于工具类的方法上，声明工具名与描述。{@code ToolConfig} 装配时反射读取，据其构建
 * {@link ToolDefinition}（detector/executor 由配置显式注入，行为不可反射故不自省）。
 * 仅作声明性元数据——LangChain4j 的 {@code @dev.langchain4j.agent.tool.Tool} 注解为同义对照，
 * 接入 LangChain4j 时可改用其注解（语义等价）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {

    /** 工具名（注册表键）。 */
    String name();

    /** 工具描述（供 LLM function-calling 选择，与 schema 一同对外）。 */
    String description() default "";
}
