package com.agentdemo007.intent;

import com.agentdemo007.gateway.config.RouteRule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由分发测试（第三层·意图→四类模型路由，§5.3.3）。
 *
 * <p>{@link RouteDispatcher} 把识别出的 {@link Intent} 映射到 {@link RouteRule.RouteType}，
 * 供 {@code ModelRouter} 选模型。注入意图不会到达本步（识别步短路），但映射上给安全默认。
 */
class RouteDispatcherTest {

    private final RouteDispatcher dispatcher = new RouteDispatcher();

    @Test
    void chitChat_toSimple() {
        assertThat(dispatcher.dispatch(Intent.CHIT_CHAT)).isEqualTo(RouteRule.RouteType.SIMPLE);
    }

    @Test
    void reasoning_toReasoning() {
        assertThat(dispatcher.dispatch(Intent.REASONING)).isEqualTo(RouteRule.RouteType.REASONING);
    }

    @Test
    void longContext_toLongContext() {
        assertThat(dispatcher.dispatch(Intent.LONG_CONTEXT)).isEqualTo(RouteRule.RouteType.LONG_CONTEXT);
    }

    @Test
    void structured_toStructured() {
        assertThat(dispatcher.dispatch(Intent.STRUCTURED_EXTRACTION)).isEqualTo(RouteRule.RouteType.STRUCTURED);
    }

    @Test
    void other_andTransfer_toSimple() {
        assertThat(dispatcher.dispatch(Intent.OTHER)).isEqualTo(RouteRule.RouteType.SIMPLE);
        assertThat(dispatcher.dispatch(Intent.TRANSFER_TO_HUMAN)).isEqualTo(RouteRule.RouteType.SIMPLE);
    }
}
