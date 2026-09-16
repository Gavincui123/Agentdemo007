package com.agentdemo007.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * SSE 流式工作线程装配（#136 富事件 SSE 真流式·[[routeplan-design]]·Slice 5）。
 *
 * <p>SseEmitter 真异步：{@link ChatController#chatStream} 返回 {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter}
 * 后，请求线程释放（Spring MVC async），流水线在**工作线程**跑、每事件 {@code send} 立即 flush、
 * 终端 {@code complete}。本 @Bean 提供工作线程池。
 *
 * <p>dev 用 {@link SimpleAsyncTaskExecutor}（每流一线程，简单；中等并发可接受——SseEmitter async
 * 已释放 Tomcat 请求线程，每流仅占一个 sse 工作线程）。高并发场景可换 {@code ThreadPoolTaskExecutor}
 * （有界池 + 队列），后置调优。线程名前缀 {@code sse-} 便于日志/审计定位。
 */
@Configuration
public class SseStreamConfig {

    @Bean
    TaskExecutor sseTaskExecutor() {
        return new SimpleAsyncTaskExecutor("sse-");
    }
}
