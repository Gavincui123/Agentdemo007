package com.agentdemo007.web;

/**
 * 对话请求体（{@code /chat}、{@code /chat/stream} 统一入口参数）。
 *
 * @param sessionId 会话标识；缺省/空白时由控制器自动生成（UUID），保证多轮上下文可续。
 * @param message  用户原始输入（作为 {@link com.agentdemo007.common.pipeline.PipelineContext#rawInput}）。
 * @param userId   当前用户 id（[[business-tools-workflow-dag]] 真接入：工作流校验订单归属用；
 *                 前端传当前登录用户 id；缺省 null→图 baked currentUserId 兜底，迭代后真鉴权强制非空）。
 */
public record ChatRequest(String sessionId, String message, String userId) {

    /** 便利构造（无 userId，userId=null；向后兼容既有 2 参调用方/测试）。 */
    public ChatRequest(String sessionId, String message) {
        this(sessionId, message, null);
    }
}
