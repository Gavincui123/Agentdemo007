package com.agentdemo007.web;

/**
 * 对话请求体（{@code /chat}、{@code /chat/stream} 统一入口参数）。
 *
 * @param sessionId 会话标识；缺省/空白时由控制器自动生成（UUID），保证多轮上下文可续。
 * @param message   用户原始输入（作为 {@link com.agentdemo007.common.pipeline.PipelineContext#rawInput}）。
 */
public record ChatRequest(String sessionId, String message) {
}
