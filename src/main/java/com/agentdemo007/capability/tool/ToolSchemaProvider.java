package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolCircuitBreaker;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 工具 schema + 执行器单源提供者（option A 数据步·{@code @Tool} beans → function schema + executor）。
 *
 * <p>持有 {@code @dev.langchain4j.agent.tool.Tool} 标注的工具 bean，经 LC4j {@link ToolSpecifications}
 * 反射每个 {@code @Tool} 方法生成 {@link ToolSpecification}（名称/描述/参数 schema 取自注解），
 * 同时留存 {@code (bean, method)} 绑定以构造 {@link DefaultToolExecutor}。两批同源反射，保证
 * {@link #allSchemas}/{@link #schemasFor} 的键与 {@link #executors} 的键<b>一致</b>——
 * 模型只可能调到有 executor 的工具（避免"调到工具无 executor→dispatch 跳过死工具"）。
 *
 * <p>退役映射（[[langchain4j-boot4-compat-findings]] ② Slice 3）：schema 生成 + 参数强转 + 反射调
 * 全归 LC4j（@Tool→schema、DefaultToolExecutor→JSON 解析+强转+反射），退役手撸 Detector/ParamParser/
 * SchemaValidator——[[dont-hardwrite-use-dep-methods]]：库方法优先，seam=委托非重写。
 *
 * <p>per-route 工具子集（参考系统 {@code allowed_tools_by_intent}）经 {@link #schemasFor(Collection)}
 * 按 {@code RoutePlan.required_tools} 取——模型只看到当前路由允许的 schema 子集；executors 全局
 * （任意子集内的工具均有 executor）。详见 [[routeplan-design]] + [[langchain4j-boot4-compat-findings]]。
 */
public class ToolSchemaProvider {

    private final Map<String, ToolBinding> bindingsByName;

    public ToolSchemaProvider(List<Object> toolBeans) {
        Map<String, ToolBinding> m = new LinkedHashMap<>();
        for (Object bean : toolBeans) {
            // ClassUtils.getUserClass 剥 CGLIB 代理取真实类（防 @Transactional 等代理致 getDeclaredMethods 漏方法）
            for (Method method : ClassUtils.getUserClass(bean).getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
                    // [[business-tools-workflow-dag]] §2.2：@ToolChannel 声明通道（RUNTIME/RAG/COMPUTE）；
                    // 缺省 COMPUTE（向后兼容既有计算工具）
                    ToolChannel tc = method.getAnnotation(ToolChannel.class);
                    ToolCategory category = (tc != null) ? tc.value() : ToolCategory.COMPUTE;
                    m.put(spec.name(), new ToolBinding(spec, bean, method, category));
                }
            }
        }
        this.bindingsByName = Map.copyOf(m);
    }

    /** 全部工具 schema。 */
    public List<ToolSpecification> allSchemas() {
        return bindingsByName.values().stream()
                .map(b -> b.spec)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 按 {@code RoutePlan.required_tools} 取允许子集（未知名跳过，不抛）。
     * null/空 → 空（不发 tools，普通对话/纯 RAG 场景）。
     */
    public List<ToolSpecification> schemasFor(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        return toolNames.stream()
                .map(name -> bindingsByName.get(name) != null ? bindingsByName.get(name).spec : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 全局工具执行器映射（name → {@link ResilientToolExecutor} 包 {@code DefaultToolExecutor}），
     * 兼容口径：无重试/无超时（既有测试/最小装配）。
     * 与 {@link #allSchemas} 同源（同批 binding），键一致。breaker 注入后 per-tool 熔断记账。
     */
    public Map<String, ResilientToolExecutor> executors(ToolCircuitBreaker breaker) {
        return executors(breaker, com.agentdemo007.resilience.RetryPolicy.noRetry(), 0L);
    }

    /**
     * 全局工具执行器映射（生产口径）：per-tool 熔断 + 分诊重试（指数退避+全抖动）+ per-call 超时。
     * 与 {@link #allSchemas} 同源（同批 binding），键一致。
     */
    public Map<String, ResilientToolExecutor> executors(ToolCircuitBreaker breaker,
                                                        com.agentdemo007.resilience.RetryPolicy retryPolicy,
                                                        long timeoutMs) {
        Map<String, ResilientToolExecutor> r = new LinkedHashMap<>();
        bindingsByName.forEach((name, b) ->
                r.put(name, new ResilientToolExecutor(propagatingExecutor(b), breaker, retryPolicy, timeoutMs)));
        return r;
    }

    /**
     * 构造 LC4j 执行原语（propagate/wrap 双开）：@Tool 方法异常以 {@code ToolExecutionException}
     * 抛出（交 {@link ToolExceptionTriage} 解包分诊重试/分类），参数异常以 {@code ToolArgumentsException}
     * 抛出（不重试、反馈 LLM 自纠正）。默认构造会把两类异常都吞成错误字符串结果——熔断记账与重试
     * 分类全部失明（2026-09-17 字节码取证），必须显式开启。
     */
    private static DefaultToolExecutor propagatingExecutor(ToolBinding b) {
        return new DefaultToolExecutor.Builder()
                .object(b.bean())
                .originalMethod(b.method())
                .methodToInvoke(b.method())
                .wrapToolArgumentsExceptions(Boolean.TRUE)
                .propagateToolExecutionExceptions(Boolean.TRUE)
                .build();
    }

    /**
     * 工具通道映射（name → category，[[business-tools-workflow-dag]] §2.2）。同源供
     * {@link ToolCallExecutor} 为每个 {@link ToolCallResult} 标 category。
     */
    public Map<String, ToolCategory> categoryMap() {
        return bindingsByName.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().category()));
    }

    /** 单工具通道；未知 name 默认 COMPUTE（②每步降级，不阻塞）。 */
    public ToolCategory categoryOf(String name) {
        ToolBinding b = bindingsByName.get(name);
        return (b != null) ? b.category() : ToolCategory.COMPUTE;
    }

    /** 工具绑定（schema + bean + method + category），同源供 schema、executor 构造与通道映射。 */
    private record ToolBinding(ToolSpecification spec, Object bean, Method method, ToolCategory category) {}
}
