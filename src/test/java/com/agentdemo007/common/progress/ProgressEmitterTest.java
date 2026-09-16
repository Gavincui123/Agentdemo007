package com.agentdemo007.common.progress;

import com.agentdemo007.common.degradation.DegradationScenario;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ProgressEmitter seam 单元测（#136 富事件 SSE 真流式·[[routeplan-design]]·Slice 1）。
 *
 * <p>ProgressEmitter 是 #136 seam：注入 PipelineContext（每请求），编排器/各步产
 * {@link ProgressEvent}→emit。{@link ProgressEmitter#NO_OP} 为同步 {@code /chat} + 单测默认
 * （不外泄、零开销）；{@code SseProgressEmitter}（后续 slice）桥接到 {@code SseEmitter} 真异步 flush。
 *
 * <p>本 slice 只钉 seam 契约：NO_OP 丢事件不抛、capturing 实现保序收集、StepFinished 在
 * DEGRADE 时携带 scenario。事件分类富化（RouteDecided/ToolCalled/RagRetrieved/ReplyReady）
 * 随各自 wiring slice 增补 sealed permits。
 */
class ProgressEmitterTest {

    @Test
    void noOp_emit_doesNotThrow() {
        ProgressEmitter noop = ProgressEmitter.NO_OP;
        assertThatCode(() -> noop.emit(new ProgressEvent.StepStarted("triage")))
                .doesNotThrowAnyException();
    }

    @Test
    void capturingEmitter_collectsEventsInOrder() {
        CapturingProgressEmitter emitter = new CapturingProgressEmitter();

        emitter.emit(new ProgressEvent.StepStarted("route"));
        emitter.emit(new ProgressEvent.StepFinished("route", ProgressEvent.Outcome.PROCEED, null));

        List<ProgressEvent> events = emitter.events();
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(ProgressEvent.StepStarted.class);
        assertThat(events.get(1)).isInstanceOf(ProgressEvent.StepFinished.class);
        assertThat(((ProgressEvent.StepStarted) events.get(0)).step()).isEqualTo("route");
        assertThat(((ProgressEvent.StepFinished) events.get(1)).outcome())
                .isEqualTo(ProgressEvent.Outcome.PROCEED);
    }

    @Test
    void stepFinished_carriesScenarioWhenDegrade() {
        CapturingProgressEmitter emitter = new CapturingProgressEmitter();

        emitter.emit(new ProgressEvent.StepFinished("rag",
                ProgressEvent.Outcome.DEGRADE, DegradationScenario.RAG_SKIP));

        ProgressEvent.StepFinished f = (ProgressEvent.StepFinished) emitter.events().get(0);
        assertThat(f.scenario()).isEqualTo(DegradationScenario.RAG_SKIP);
    }

    /** 测试用 capturing 实现：收集事件、保序、供断言（生产侧由 SseProgressEmitter 桥接 SseEmitter）。 */
    static final class CapturingProgressEmitter implements ProgressEmitter {
        private final List<ProgressEvent> events = new ArrayList<>();

        @Override
        public void emit(ProgressEvent event) {
            events.add(event);
        }

        List<ProgressEvent> events() {
            return events;
        }
    }
}
