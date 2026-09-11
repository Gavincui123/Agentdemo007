package com.agentdemo007.feedback;

import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 反馈控制器（Phase 15·T67 微调闭环·在线反馈入口）。
 *
 * <p>{@code POST /feedback}：校验入参（traceId/prompt/reply 非空、label 非空 → 400 BAD_REQUEST），
 * 委托 {@link FeedbackCollector} 采集落池；结果恒 HTTP 200 + code=0，经 {@link FeedbackResponse#collected}
 * 透出（②降级：落池失败不 5xx，仅 collected=false）。鉴权在 T72 安全巡检收口（先开放便于联调）。
 */
@RestController
public class FeedbackController {

    private final FeedbackCollector collector;

    public FeedbackController(FeedbackCollector collector) {
        this.collector = collector;
    }

    @PostMapping("/feedback")
    public UnifiedResponse feedback(@RequestBody FeedbackRequest request) {
        if (!isValid(request)) {
            return UnifiedResponse.error(ErrorCode.BAD_REQUEST);
        }
        boolean collected = collector.collect(request);
        return UnifiedResponse.success(new FeedbackResponse(collected));
    }

    private boolean isValid(FeedbackRequest request) {
        return request != null
                && isNotBlank(request.traceId())
                && isNotBlank(request.prompt())
                && isNotBlank(request.reply())
                && request.label() != null;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
