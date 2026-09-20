package com.agentdemo007.eval;

import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import com.agentdemo007.gate.AccessGateService;
import com.agentdemo007.gate.GateRule;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评测端点控制器（Phase 15·T70 + 2026-09-17 黄金集 Nacos 动态化 + 2026-09-18 异步化）。
 *
 * <p><b>POST /eval/run</b>：鉴权 → stage 过滤 → 启动异步评测作业，<b>秒回</b>当前进度快照
 * （不在 HTTP 请求里同步跑——全量黄金集逐例真 LLM 调用曾拖爆前端超时且无进度）。
 * 已有作业在跑 → CONFLICT 话术。作业内部逐 stage：Nacos 实时拉数据（失败回退本地 classpath）
 * → 解析 → 驱动真实流水线 → 产出 {@link StageReport}。
 *
 * <p><b>GET /eval/progress</b>：轮询进度快照（running/已完成数/当前 stage/已完成分环节结果/
 * 跳过列表/最终报告）。前端每秒拉取实时亮灯；页面刷新后重拉无缝续看。
 *
 * <p>鉴权两端点同形（{@code X-Eval-Token}，{@link EvalAuthenticator} 逐字比对）：未授权 →
 * {@code code=401} 不泄露报告。stage 过滤：请求体为 JSON 字符串数组（如 {@code ["rag-redteam"]}，
 * 也接受完整资源路径）；缺省跑全量黄金集；含无法匹配的 stage → BAD_REQUEST（不静默跑空集）。
 *
 * <p><b>IP 封禁（2026-09-18 用户裁决·统一旋钮）</b>：闸口 {@code enabled=true}（发布口径）时，
 * eval 全端点<b>仅限本机</b>（{@code AccessGateService#isLoopbackClient}）——外部 IP 一律 401
 * 话术，防止发布后陌生人消耗评测 LLM 成本；旋钮关闭（dev）行为不变。先于令牌校验（纵深防御：
 * 令牌泄露也无法从外部触发）。
 *
 * <p>对外统一 {@link UnifiedResponse}（第四原则·收口）。
 */
@RestController
public class EvalController {

    private final EvalAuthenticator authenticator;
    private final EvalSuite evalSuite;
    private final EvalJobManager jobManager;
    private final AccessGateService gateService;

    /** Spring 装配入口（双构造器须显式指定）；gateService 经 GateConfig 提供。 */
    @org.springframework.beans.factory.annotation.Autowired
    public EvalController(EvalAuthenticator authenticator, EvalSuite evalSuite, EvalJobManager jobManager,
                          AccessGateService gateService) {
        this.authenticator = authenticator;
        this.evalSuite = evalSuite;
        this.jobManager = jobManager;
        this.gateService = gateService;
    }

    /** 兼容构造（既有测试）：恒关闸（旋钮 off）→ IP 封禁不生效，行为同旧。 */
    public EvalController(EvalAuthenticator authenticator, EvalSuite evalSuite, EvalJobManager jobManager) {
        this(authenticator, evalSuite, jobManager, new AccessGateService(
                GateRule.localDefaults(false, "", 5,
                        GateRule.DEFAULT_REQUIRED_MESSAGE, GateRule.DEFAULT_EXHAUSTED_MESSAGE),
                java.time.Clock.systemUTC(), null, null, null));
    }

    /** 统一旋钮 IP 封禁：闸口开启且非本机来源 → 拒绝（先于令牌校验）。 */
    private UnifiedResponse ipBlocked(HttpServletRequest request) {
        if (gateService.current().active() && !AccessGateService.isLoopbackClient(request)) {
            return UnifiedResponse.error(ErrorCode.UNAUTHORIZED, "评测功能仅限本机使用，外部访问已关闭");
        }
        return null;
    }

    @PostMapping("/eval/run")
    public UnifiedResponse run(@RequestHeader(value = "X-Eval-Token", required = false) String token,
                               @RequestBody(required = false) List<String> stages,
                               HttpServletRequest request) {
        UnifiedResponse blocked = ipBlocked(request);
        if (blocked != null) {
            return blocked;
        }
        if (!authenticator.authenticate(token)) {
            return UnifiedResponse.error(ErrorCode.UNAUTHORIZED);
        }
        List<String> resources = selectResources(stages);
        if (resources == null) {
            return UnifiedResponse.error(ErrorCode.BAD_REQUEST);
        }
        if (!jobManager.start(resources)) {
            return UnifiedResponse.error(ErrorCode.CONFLICT, "评测正在进行中，请等待完成后再启动");
        }
        return UnifiedResponse.success(jobManager.progress());
    }

    @GetMapping("/eval/progress")
    public UnifiedResponse progress(@RequestHeader(value = "X-Eval-Token", required = false) String token,
                                    HttpServletRequest request) {
        UnifiedResponse blocked = ipBlocked(request);
        if (blocked != null) {
            return blocked;
        }
        if (!authenticator.authenticate(token)) {
            return UnifiedResponse.error(ErrorCode.UNAUTHORIZED);
        }
        return UnifiedResponse.success(jobManager.progress());
    }

    /**
     * stage 过滤：请求缺省 → 全量黄金集；非空 → 按 stage 名（{@code rag-redteam}）或完整资源路径
     * 匹配；有请求项但零匹配 → {@code null}（调用方转 BAD_REQUEST），避免静默空报告掩盖拼写错误。
     */
    private List<String> selectResources(List<String> stages) {
        if (stages == null || stages.isEmpty()) {
            return evalSuite.resources();
        }
        List<String> matched = evalSuite.resources().stream()
                .filter(r -> stages.contains(stageName(r)) || stages.contains(r))
                .toList();
        return matched.isEmpty() ? null : matched;
    }

    private static String stageName(String resource) {
        return resource.substring(resource.lastIndexOf('/') + 1, resource.lastIndexOf('.'));
    }
}
