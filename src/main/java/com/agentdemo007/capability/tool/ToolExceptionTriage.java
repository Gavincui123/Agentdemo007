package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ExceptionCategory;
import com.agentdemo007.resilience.ExceptionTriage;
import com.agentdemo007.resilience.TriageResult;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;

/**
 * 工具调用异常分诊器（{@link ExceptionTriage} 工具扩展）：在基线分诊前处理工具特有异常，
 * 驱动 {@code ResilientToolExecutor} 的重试决策。
 *
 * <ul>
 *   <li>{@link ToolExecutionException}（{@code DefaultToolExecutor} propagate=true 时包装
 *       @Tool 方法异常）→ 解包根因重入分诊（真实归因：HTTP/超时/业务异常，套层不改判）；</li>
 *   <li>{@link ToolArgumentsException}（模型产出参数 JSON 解析/强转失败）→ 不可重试
 *       （重试同参数无意义），交 Agent loop 反馈 LLM 自纠正；</li>
 *   <li>其余（{@code ToolHttpException}/{@code ToolTimeoutException}/瞬态/未知）→ 基线分诊
 *       （基线已含 HTTP 按状态码与超时的重试规则）。</li>
 * </ul>
 */
public class ToolExceptionTriage extends ExceptionTriage {

    @Override
    public TriageResult triage(Throwable t) {
        if (t instanceof ToolExecutionException te && te.getCause() != null) {
            return triage(te.getCause()); // 解包归因：DefaultToolExecutor 只包一层真实异常
        }
        if (t instanceof ToolArgumentsException) {
            return TriageResult.of(ExceptionCategory.NON_RETRYABLE_CLIENT, -1L,
                    "工具参数非法：反馈 LLM 自纠正，不重试");
        }
        return super.triage(t);
    }
}
