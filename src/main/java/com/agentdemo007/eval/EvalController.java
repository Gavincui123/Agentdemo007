package com.agentdemo007.eval;

import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测端点控制器（Phase 15·T70）。
 *
 * <p>{@code POST /eval/run}：批量执行黄金数据集评测，输出 {@link EvalReport}（各 stage 通过率/per-case
 * 结果）。鉴权保护（验收：生产环境无鉴权不可访问 /eval/run）：
 * <ol>
 *   <li>取 {@code X-Eval-Token} 请求头 → {@link EvalAuthenticator#authenticate}；</li>
 *   <li>未授权（null/错 token）→ {@link UnifiedResponse#error(ErrorCode) UNAUTHORIZED}
 *       （code=401，data=null，不返回报告——与兄弟控制器同形 HTTP 200 + code 体内表达，
 *       ②降级不 5xx，"不可访问"=不外泄评测报告）；</li>
 *   <li>授权 → 加载 {@link EvalSuite} 各资源 → {@link EvalExecutor#run} → {@code success(report)}。</li>
 * </ol>
 *
 * <p>对外统一 {@link UnifiedResponse}（第四原则·收口）；{@link EvalAuthenticator} seam + dev 默认 @Bean
 * （读 {@code agentdemo.eval.token}，prod 覆盖）；{@link EvalSuite} 强类型资源集（非裸 List/Map）。
 */
@RestController
public class EvalController {

    private final EvalExecutor executor;
    private final EvalAuthenticator authenticator;
    private final EvalSuite evalSuite;

    public EvalController(EvalExecutor executor, EvalAuthenticator authenticator, EvalSuite evalSuite) {
        this.executor = executor;
        this.authenticator = authenticator;
        this.evalSuite = evalSuite;
    }

    @PostMapping("/eval/run")
    public UnifiedResponse run(@RequestHeader(value = "X-Eval-Token", required = false) String token) {
        if (!authenticator.authenticate(token)) {
            return UnifiedResponse.error(ErrorCode.UNAUTHORIZED);
        }
        List<EvalFile> files = new ArrayList<>();
        for (String resource : evalSuite.resources()) {
            files.add(executor.loadFile(resource));
        }
        return UnifiedResponse.success(executor.run(files));
    }
}
