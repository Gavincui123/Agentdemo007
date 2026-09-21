package com.agentdemo007.session.summary;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 业务键提取器（Phase 22·T101 摘要业务键白名单断言 + T102 画像守门的共用词法）。
 *
 * <p>业务键 = 业务系统才能解释的实体标识（订单号/工单号/退款单号等 {@code XXX-123} 形态）。
 * 两类消费方：
 * <ul>
 *   <li><b>滚动摘要白名单</b>——压缩前文本中出现的业务键，压缩后必须仍在（运行时缺啥补啥，
 *       golden 测试钉死）；摘要漏写订单号时"那单到哪了"的实体消解仍由 L0 注册表兜底，
 *       白名单是第二道保险而非唯一防线；</li>
 *   <li><b>画像守门</b>——画像只存"业务系统查不到、只影响表达"的偏好，出现业务键即拒收
 *       （实体归 L0 注册表与业务库，画像承载会形成第二真相源）。</li>
 * </ul>
 * 词表进代码（有界词表原则，同 KbLevel）：{@code [A-Z]{2,6}-\d{1,10}} 覆盖 ORD-001/HITL-12/
 * REF-2024 等项目全部 mock 单号形态；纯数字长单号易与手机号/金额误伤，不纳入。
 */
public final class BusinessKeyExtractor {

    /** 业务键形态：大写字母段 2~6 位 + 连字符 + 数字 1~10 位（ORD-001、HITL-12、RF-7）。 */
    private static final Pattern BUSINESS_KEY = Pattern.compile("\\b[A-Z]{2,6}-\\d{1,10}\\b");

    private BusinessKeyExtractor() {
    }

    /** 提取文本中出现的全部业务键（去重保序；null/空白 → 空集）。 */
    public static Set<String> extract(String text) {
        Set<String> keys = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return keys;
        }
        Matcher m = BUSINESS_KEY.matcher(text);
        while (m.find()) {
            keys.add(m.group());
        }
        return keys;
    }

    /** 提取多段文本（如一轮次消息列表）中出现的全部业务键并集。 */
    public static Set<String> extractAll(List<String> texts) {
        Set<String> keys = new LinkedHashSet<>();
        if (texts != null) {
            for (String t : texts) {
                keys.addAll(extract(t));
            }
        }
        return keys;
    }

    /** 文本是否含业务键（守门快判）。 */
    public static boolean contains(String text) {
        return text != null && !text.isBlank() && BUSINESS_KEY.matcher(text).find();
    }
}
