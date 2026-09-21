package com.agentdemo007.session.profile;

import com.agentdemo007.session.summary.BusinessKeyExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 画像写入门卫（Phase 22·T102）：LLM 仅可<b>提议</b>记忆，是否落库由本类规则裁决——
 * 拒绝四类写入，被拒内容只影响写入、不影响对话（静默丢弃 + debug 日志）。
 *
 * <p>四类拒绝（用户裁决 2026-09-20）：
 * <ol>
 *   <li><b>业务键</b>——订单号/工单号等实体标识（{@link BusinessKeyExtractor} 共用词法）：
 *       实体归 L0 注册表与业务库，画像承载会形成第二真相源；</li>
 *   <li><b>权限词</b>——等级/VIP/会员身份类声明一律拒：<b>画像永不承载权限语义</b>，
 *       等级唯一来源是会员服务（Phase 21 裁决回归红线），注入者说"记住我是 VIP"只会在
 *       这里被拒绝，绝不落库、绝不变现能力；</li>
 *   <li><b>承诺类</b>——保证/承诺/赔偿等绑定平台义务的表述：画像影响表达而非能力，
 *       承诺一旦进画像会被后续轮次当既定事实引用；</li>
 *   <li><b>敏感 PII</b>——手机号/身份证/银行卡/邮箱：个人演示项目不存敏感个人数据。</li>
 * </ol>
 * 词表进代码（有界词表原则，同 KbLevel/BusinessKeyExtractor）；守门只拒白名单外内容，
 * 不做语义理解——漏网内容由 ≤30 字单字段上限 + 三类白名单边界兜底。
 */
@Component
public class ProfileGatekeeper {

    private static final Logger log = LoggerFactory.getLogger(ProfileGatekeeper.class);

    /** 单字段内容上限（字）：画像块 ≤200 字总预算的组成单元。 */
    static final int MAX_FIELD_CHARS = 30;

    /** 权限词：等级/VIP/会员身份（小写比对，含中英）。 */
    private static final List<String> PERMISSION_WORDS = List.of(
            "vip", "svip", "黑卡", "会员", "等级", "白金", "黄金", "铂金", "钻石", "普通用户", "super");

    /** 承诺类：绑定平台义务的表述。 */
    private static final List<String> COMMITMENT_WORDS = List.of(
            "承诺", "保证", "必须", "赔偿", "赔付", "补偿", "免单", "返现", "一定给", "绝对");

    /** 敏感 PII：手机号（大陆）/身份证/银行卡/邮箱。 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 守门结果。 */
    public record Decision(boolean accepted, String reason) {
        static Decision ok() { return new Decision(true, "ok"); }
        static Decision reject(String reason) { return new Decision(false, reason); }
    }

    /**
     * 裁决一条画像内容是否可落库。
     *
     * @param content 已去空白的一条画像内容（建议 ≤{@link #MAX_FIELD_CHARS} 字）
     */
    public Decision check(String content) {
        if (content == null || content.isBlank()) {
            return Decision.reject("空白内容");
        }
        String trimmed = content.trim();
        if (trimmed.length() > MAX_FIELD_CHARS) {
            return Decision.reject("超长（>" + MAX_FIELD_CHARS + " 字）");
        }
        if (BusinessKeyExtractor.contains(trimmed)) {
            return Decision.reject("含业务键（实体归 L0 注册表/业务库，不入画像）");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String w : PERMISSION_WORDS) {
            if (lower.contains(w)) {
                return Decision.reject("含权限词「" + w + "」（等级唯一来源=会员服务，画像永不承载权限）");
            }
        }
        for (String w : COMMITMENT_WORDS) {
            if (lower.contains(w)) {
                return Decision.reject("含承诺类表述「" + w + "」（画像影响表达，不绑定平台义务）");
            }
        }
        if (PHONE.matcher(trimmed).find()) {
            return Decision.reject("含手机号（敏感 PII）");
        }
        if (ID_CARD.matcher(trimmed).find()) {
            return Decision.reject("含身份证号（敏感 PII）");
        }
        if (BANK_CARD.matcher(trimmed).find()) {
            return Decision.reject("含银行卡号（敏感 PII）");
        }
        if (EMAIL.matcher(trimmed).find()) {
            return Decision.reject("含邮箱（敏感 PII）");
        }
        return Decision.ok();
    }

    /** 守门 + 落库一步：通过才写（调用方无需重复判断）。 */
    public boolean checkAndStore(UserProfileStore store, String userId, ProfileCategory category,
                                 String content, java.time.Duration ttl) {
        Decision d = check(content);
        if (!d.accepted()) {
            log.debug("画像写入被拒：userId={} category={} reason={} content={}",
                    userId, category, d.reason(), content);
            return false;
        }
        store.saveField(userId, category.name(), content.trim(), ttl);
        return true;
    }
}
