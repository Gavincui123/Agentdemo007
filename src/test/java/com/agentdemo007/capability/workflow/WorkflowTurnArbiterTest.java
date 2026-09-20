package com.agentdemo007.capability.workflow;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.intent.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话仲裁器测试（2026-09-18 方案A·用户裁决）。
 *
 * <p>覆盖：LLM 缺席停用（恒 empty → 确定性回退）/ 合法 JSON 解析 / <b>宽松解析</b>（JSON 藏在
 * 说明文字里也能取）/ BIND_RUN 缺合法 intent 拒收 / 判定越界拒收 / LLM 异常与空回复回退。
 * 降级语义是方案A的底线：仲裁任何失败都必须落到 Optional.empty()，由调用方走原确定性分支。
 */
class WorkflowTurnArbiterTest {

    private static final WorkflowSubmissionRegistry.Entry ACTIVE = new WorkflowSubmissionRegistry.Entry(
            "REFUND_REQUEST", "ORD-001", "WF-1", "sess-1", 1L);

    private static PipelineContext ctx(String raw) {
        return new PipelineContext("sess-1", raw);
    }

    @Test
    void llmAbsent_disabled_alwaysEmpty() {
        WorkflowTurnArbiter arbiter = new WorkflowTurnArbiter(null);

        assertThat(arbiter.arbitrate(ctx("算了不退了"), null, ACTIVE, List.of("refund_request")))
                .isEmpty();
    }

    @Test
    void validJson_withdraw_parsed() {
        ChatLlmService llm = mock(ChatLlmService.class);
        when(llm.chatRaw(anyString(), any(Intent.class), eq("会话仲裁")))
                .thenReturn("{\"decision\":\"WITHDRAW\",\"intent\":null,\"reason\":\"用户明确放弃\"}");
        WorkflowTurnArbiter arbiter = new WorkflowTurnArbiter(llm);

        Optional<WorkflowTurnArbiter.Arbitration> out =
                arbiter.arbitrate(ctx("算了不退了"), null, ACTIVE, List.of("refund_request"));

        assertThat(out).isPresent();
        assertThat(out.orElseThrow().isWithdraw()).isTrue();
        assertThat(out.orElseThrow().reason()).contains("放弃");
    }

    @Test
    void jsonEmbeddedInProse_lenientParsed() {
        ChatLlmService llm = mock(ChatLlmService.class);
        when(llm.chatRaw(anyString(), any(Intent.class), eq("会话仲裁")))
                .thenReturn("根据上下文判断如下：\n{\"decision\":\"BIND_RUN\",\"intent\":\"return_request\",\"reason\":\"承接退货澄清\"}\n以上。");
        WorkflowTurnArbiter arbiter = new WorkflowTurnArbiter(llm);

        Optional<WorkflowTurnArbiter.Arbitration> out =
                arbiter.arbitrate(ctx("ORD-001 退货"), null, null, List.of("return_request", "refund_request"));

        assertThat(out).isPresent();
        assertThat(out.orElseThrow().isBindRun()).isTrue();
        assertThat(out.orElseThrow().intent()).isEqualTo("return_request");
    }

    @Test
    void bindRun_missingLegalIntent_rejected() {
        ChatLlmService llm = mock(ChatLlmService.class);
        when(llm.chatRaw(anyString(), any(Intent.class), eq("会话仲裁")))
                .thenReturn("{\"decision\":\"BIND_RUN\",\"intent\":\"faq\",\"reason\":\"越权意图\"}");
        WorkflowTurnArbiter arbiter = new WorkflowTurnArbiter(llm);

        assertThat(arbiter.arbitrate(ctx("ORD-001"), null, null, List.of("refund_request"))).isEmpty();
    }

    @Test
    void unknownDecision_rejected() {
        ChatLlmService llm = mock(ChatLlmService.class);
        when(llm.chatRaw(anyString(), any(Intent.class), eq("会话仲裁")))
                .thenReturn("{\"decision\":\"RUN_EVERYTHING\",\"intent\":null,\"reason\":\"越界\"}");
        WorkflowTurnArbiter arbiter = new WorkflowTurnArbiter(llm);

        assertThat(arbiter.arbitrate(ctx("x"), null, null, List.of())).isEmpty();
    }

    @Test
    void llmThrows_fallsBackToEmpty() {
        ChatLlmService llm = mock(ChatLlmService.class);
        when(llm.chatRaw(anyString(), any(Intent.class), eq("会话仲裁")))
                .thenThrow(new RuntimeException("model down"));
        WorkflowTurnArbiter arbiter = new WorkflowTurnArbiter(llm);

        assertThat(arbiter.arbitrate(ctx("x"), null, ACTIVE, List.of())).isEmpty();
    }

    @Test
    void blankReply_fallsBackToEmpty() {
        ChatLlmService llm = mock(ChatLlmService.class);
        when(llm.chatRaw(anyString(), any(Intent.class), eq("会话仲裁"))).thenReturn("  ");
        WorkflowTurnArbiter arbiter = new WorkflowTurnArbiter(llm);

        assertThat(arbiter.arbitrate(ctx("x"), null, ACTIVE, List.of())).isEmpty();
    }
}
