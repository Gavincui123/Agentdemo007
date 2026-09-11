package com.agentdemo007.feedback;

/**
 * 反馈响应（Phase 15·T67 在线反馈出参，第四原则·强类型收口，非 Map）。
 *
 * <p>恒 HTTP 200 + code=0（②降级：落池失败不 5xx），仅经 {@code collected} 透出是否已落池。
 *
 * @param collected 是否成功采集落池
 */
public record FeedbackResponse(boolean collected) {
}
