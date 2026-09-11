package com.agentdemo007.capability.hitl;

import com.agentdemo007.intent.Intent;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * HITL 触发判定器（第四层·高风险操作识别与确认请求生成）。
 *
 * <p>判定是否需要人工确认（§5.4.3 "高风险操作触发"）：
 * <ul>
 *   <li>意图为 {@link Intent#TRANSFER_TO_HUMAN}（转人工）→ 触发；</li>
 *   <li>或用户问题命中高风险关键词（转人工/人工客服/投诉/举报/紧急/报警）→ 触发。</li>
 * </ul>
 * 注入意图（{@link Intent#INJECTION}）不触发 HITL——注入路径在 {@code IntentRecognitionStep}(@Order 500)
 * 已短路为零 LLM，HITL(@Order 610) 收不到；即便收到也返回 false（注入独立处置）。
 *
 * <p>触发后由 {@link #buildRequest} 产出 {@link HitlRequest}（含 reason/riskLevel）。
 * 引擎无关：判定纯规则、无 LLM；高风险关键词库可经 Nacos 热更新扩展（prod 薄层覆盖）。
 */
@Component
public class HitlHandler {

    private static final String[] HIGH_RISK_KEYWORDS = {
            "转人工", "人工客服", "投诉", "举报", "紧急", "报警"
    };

    /**
     * 是否需要人工确认。
     *
     * @param query  标准化查询（缺失回退由调用方处理）
     * @param intent 识别意图（可能为 null）
     */
    public boolean needsReview(String query, Intent intent) {
        if (intent == Intent.TRANSFER_TO_HUMAN) {
            return true;
        }
        if (intent == Intent.INJECTION) {
            return false;
        }
        return matchesHighRiskKeyword(query);
    }

    /** 构建确认请求（触发后调用）。 */
    public HitlRequest buildRequest(String sessionId, String query, Intent intent) {
        String reason = (intent == Intent.TRANSFER_TO_HUMAN)
                ? "用户请求转人工服务"
                : "命中高风险关键词，需人工确认";
        return new HitlRequest(sessionId, query, reason, HitlRequest.RISK_HIGH);
    }

    private boolean matchesHighRiskKeyword(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        for (String kw : HIGH_RISK_KEYWORDS) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
