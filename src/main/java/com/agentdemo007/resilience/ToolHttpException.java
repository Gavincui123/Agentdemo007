package com.agentdemo007.resilience;

/**
 * 工具调用外部系统 HTTP 状态异常（Phase 9 工具韧性扩展）。
 *
 * <p>{@code @Tool} 方法内部调用外部系统（订单/物流/商品服务等）收到 4xx/5xx 时抛出本类型，
 * 携带状态码供 {@link ExceptionTriage} 分诊：
 * <ul>
 *   <li>5xx / 408 / 429 → 瞬态可重试（指数退避 + 全抖动重试）；</li>
 *   <li>其余 4xx → 客户端错误不可重试（重试同参数无意义），错误结果反馈 LLM 换策略/澄清。</li>
 * </ul>
 *
 * <p>当前业务 @Tool 为 JVM 内 mock 服务（无真实 HTTP 出站）；接真外部系统时由工具实现抛出
 * 本类型，韧性层（重试/熔断/错误回喂）自动接管，无需改调用链。
 */
public class ToolHttpException extends RuntimeException {

    private final int status;

    public ToolHttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ToolHttpException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** HTTP 状态码（4xx/5xx）。 */
    public int status() {
        return status;
    }
}
