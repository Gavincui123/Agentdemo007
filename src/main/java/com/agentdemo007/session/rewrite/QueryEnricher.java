package com.agentdemo007.session.rewrite;

import com.agentdemo007.session.model.QueryEnrichment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * 查询补全器（第二层·Phase 20·T88 约束改写）。
 *
 * <p>从用户查询**抽取**精确词与时间线，产出 {@link QueryEnrichment}——只补全不替换、
 * 只抽事实**不下业务结论**。原查询保留于 {@code rawInput}，本器只附补充槽，不覆盖 {@code standardQuery}。
 *
 * <p>dev 确定性抽取（正则 ASCII 标识符 + CJK 词典精确词 + 时间线正则），prod 覆盖为 NER/分词
 * （随 LangChain4j 接入延后）。抽取口径与 Hybrid RAG 关键词/规则路由通道一致（T89）：
 * <ul>
 *   <li>精确词 = ASCII 字母数字标识符（订单号/型号，含数字、长度≥4，排除 4 位纯年份）+ CJK 词典
 *       精确词（发票类型等，可配置扩展）；</li>
 *   <li>时间线 = Q1-Q4 / 20xx年 / 上个月 等时间指代。</li>
 * </ul>
 * 纯 CJK 自然语言（如"退款流程"）无精确词 → 空槽，RAG 走纯语义向量通道。
 */
@Component
public class QueryEnricher {

    /** ASCII 字母数字标识符（订单号/型号）。 */
    private static final Pattern ID_TERM = Pattern.compile("[A-Za-z0-9]{4,}");
    /** 4 位纯年份（20xx），排除出精确词（归时间线）。 */
    private static final Pattern FOUR_DIGIT_YEAR = Pattern.compile("20\\d{2}");
    /** 时间线指代。 */
    private static final Pattern TIMELINE =
            Pattern.compile("\\bQ[1-4]\\b|20\\d{2}年|上个月|本月|最近|今天|今年|上个季度|上个星期");
    /** CJK 词典精确词（发票类型等，dev 内置；prod 经配置扩展）。 */
    private static final List<String> DICTIONARY = List.of(
            "增值税专用发票", "增值税普通发票", "电子发票", "普通发票");

    public QueryEnrichment enrich(String query) {
        if (query == null || query.isBlank()) {
            return QueryEnrichment.EMPTY;
        }
        Set<String> keywords = new LinkedHashSet<>();
        ID_TERM.matcher(query).results()
                .filter(mr -> mr.group().length() >= 4)
                .filter(mr -> mr.group().matches(".*\\d.*"))           // 须含数字（订单号/型号特征）
                .filter(mr -> !FOUR_DIGIT_YEAR.matcher(mr.group()).matches()) // 排除 4 位纯年份
                .filter(mr -> !isAmountToken(query, mr)) // 排除金额（纯数字+金额上下文），不排标识符
                .forEach(mr -> keywords.add(mr.group().toUpperCase())); // 规范大写，匹配大写种子（code-review #6）
        for (String term : DICTIONARY) {
            if (query.contains(term)) {
                keywords.add(term);
            }
        }
        var tm = TIMELINE.matcher(query);
        String timeline = tm.find() ? tm.group() : null;
        return new QueryEnrichment(List.copyOf(keywords), timeline);
    }

    /**
     * 纯数字 token 是否处金额上下文（前接 满/达/约/超 或 消费/金额/超过；后接 元/万/块/亿）。
     * 金额非订单号/型号——排除以免误命中含该数字串的无关片段。含字母的标识符（订单号/型号）
     * 不判（始终保留）——与既有 {@code pureDigitOrderId_extractedAsKeyword} 行为一致。
     */
    private static boolean isAmountToken(String query, MatchResult mr) {
        String token = mr.group();
        if (!token.matches("\\d+")) {
            return false; // 含字母 → 非金额
        }
        int start = mr.start();
        int end = mr.end();
        if (end < query.length()) {
            char c = query.charAt(end);
            if (c == '元' || c == '万' || c == '块' || c == '亿') {
                return true; // 后接货币单位
            }
        }
        if (start >= 1) {
            char p = query.charAt(start - 1);
            if (p == '满' || p == '达' || p == '约' || p == '超') {
                return true; // 前接单字金额动词
            }
        }
        if (start >= 2) {
            String two = query.substring(start - 2, start);
            if (two.equals("消费") || two.equals("金额") || two.equals("超过")) {
                return true; // 前接双字金额动词
            }
        }
        return false;
    }
}
