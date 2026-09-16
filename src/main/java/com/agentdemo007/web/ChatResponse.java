package com.agentdemo007.web;

import java.util.List;

/**
 * 对话响应数据体（{@code /chat}、{@code /chat/stream} 的 {@code data} 载荷）。
 *
 * <p>对外收口形状与接入层注入/越界短路的 {@code {reply, degraded, scenario}} 一致：
 * {@code code} 恒 0（话术短路不暴露技术码，HTTP 200），降级信息仅经 {@code degraded}/
 * {@code scenario} 元字段透出，供客户端可选展示"降级态"，不影响对话主流程。
 *
 * <p>Phase 20 citation：{@code citations} 携带 RAG 命中片段的可追溯引用（来源+摘要串），
 * 仅在正常/降级回复时非空，接入层短路话术时 {@code data=null}（无 citations）。强类型字段收口
 * （④，非混入 reply 字符串）；<b>可追溯 ≠ 一定正确</b>——仅标示出处，不背书回复绝对正确。
 *
 * @param sessionId 回传会话标识（缺省生成时客户端可续用）；接入层短路时为 {@code null}。
 * @param reply     最终回复内容（正常回复或话术兜底，永不为技术错误码）。
 * @param degraded  是否经历降级/短路。
 * @param scenario  降级场景名（无降级为 {@code null}）。
 * @param citations RAG 命中来源引用（来源+摘要串）；无 RAG 命中或话术短路时为空列表。
 * @param totalMs   本轮总耗时（后端收到请求→终端回复就绪，毫秒）；超时兜底话术≈SSE 超时值。
 * @param firstTokenMs 首个流式 token 相对请求的耗时（毫秒）；同步 /chat 与无流式 token 时为 {@code null}。
 */
public record ChatResponse(String sessionId, String reply, boolean degraded, String scenario,
                           List<String> citations, long totalMs, Long firstTokenMs) {

    /** 兼容构造（无计时）：既有测试/调用点零改动。 */
    public ChatResponse(String sessionId, String reply, boolean degraded, String scenario,
                        List<String> citations) {
        this(sessionId, reply, degraded, scenario, citations, 0L, null);
    }
}
