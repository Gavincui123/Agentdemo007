package com.agentdemo007.capability.hitl;

import com.agentdemo007.common.pipeline.PipelineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HITL 业务幂等键解析器（2026-09-18 L2 挂起-恢复配套·用户裁决）。
 *
 * <p><b>键必须锚定业务唯一属性而非会话</b>：sessionId+query 组合在用户重新发起会话后失效，
 * 无法防重复审批。面向退货/退款场景，以<b>订单号（业务实体）+ 业务动作</b>组合成键——
 * 同一订单的同一售后动作，无论用户重开会话/换会话/重复发送，键恒定：
 * <ul>
 *   <li>订单号：从用户原话/标准化 Query 提取 {@code ORD-*} 模式（trim+大写归一，镜像
 *       {@code OrderQueryService} 防御性归一化），<b>跨会话稳定</b>；</li>
 *   <li>业务动作：routePlan 候选 route id（如 refund）优先，回退意图枚举名；</li>
 *   <li>兜底：无订单号（提取失败）→ 查询文本摘要（md5 前 16 位，同样跨会话稳定——
 *       同一话术重发仍命中同键），并如实降级不冒充业务键。</li>
 * </ul>
 * 键格式：{@code hitl:{action}:{entity}}。消费方：{@code HitlStep}（建单幂等）+
 * {@code HitlResumeService}（恢复漂移校验锚点）。
 */
@Component
public class HitlIdempotencyKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(HitlIdempotencyKeyResolver.class);

    /** 订单号模式（mock 口径 ORD-001；真系统接入时按新单号格式扩此模式，单点改造）。 */
    private static final Pattern ORDER_ID = Pattern.compile("(?i)\\bORD-[A-Z0-9]+(?:-[A-Z0-9]+)*\\b");

    private static final String PREFIX = "hitl:";

    /**
     * 解析业务幂等键（确定性：同输入恒同键；绝不返回 null/blank——无订单号时降级查询摘要）。
     */
    public String resolve(PipelineContext context) {
        String query = firstNonBlank(context.rawInput(),
                context.standardQuery() != null ? context.standardQuery().text() : null);
        String action = resolveAction(context);
        String entity = resolveEntity(query);
        String key = PREFIX + action + ":" + entity;
        log.debug("HITL 业务幂等键：key={} action={} entity={}", key, action, entity);
        return key;
    }

    /** 业务动作：routePlan 候选 route id 优先（售后语义最准），回退意图枚举名。 */
    private String resolveAction(PipelineContext context) {
        if (context.routePlan() != null
                && context.routePlan().candidate() != null
                && context.routePlan().candidate().intent() != null
                && !context.routePlan().candidate().intent().isBlank()) {
            return normalize(context.routePlan().candidate().intent());
        }
        return (context.intent() != null) ? context.intent().name() : "UNKNOWN";
    }

    /** 业务实体：订单号归一提取优先；无订单号降级查询文本摘要（跨会话仍稳定）。 */
    private String resolveEntity(String query) {
        if (query != null) {
            Matcher m = ORDER_ID.matcher(query);
            if (m.find()) {
                return normalize(m.group()); // trim+大写（ord-001 → ORD-001，镜像订单查询防御归一）
            }
        }
        String digest = DigestUtils.md5DigestAsHex(
                ((query == null) ? "" : query.trim()).getBytes(StandardCharsets.UTF_8));
        return "q:" + digest.substring(0, 16);
    }

    private static String normalize(String s) {
        return s.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
