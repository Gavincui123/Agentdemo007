package com.agentdemo007.gate;

/**
 * 访问闸口规则（2026-09-18 部署闸门定案：登录页口令 + 按 IP 每日 N 轮）。
 *
 * <p>字段用包装类型——Nacos 配置支持<b>部分更新</b>：{@code null} 字段经 {@link #normalize}
 * 沿用当前生效值（例如只改 dailyLimit 不必重发整份 JSON）。本地 application.yml 为兜底初始值；
 * Nacos dataId {@code agentdemo-gate.json} 为热更新源（Listener 推送，改配置秒级生效不重启）。
 *
 * @param enabled          闸口总开关（false=全开放，等同无闸）
 * @param accessCode       访问口令（前端登录页录入，出站带 {@code X-Access-Code} 头逐字比对）
 * @param dailyLimit       每 IP 每自然日（Asia/Shanghai）可发起的对话轮数上限
 * @param requiredMessage  未带/带错口令时的面向用户话术
 * @param exhaustedMessage 当日额度用尽时的面向用户话术
 */
public record GateRule(Boolean enabled, String accessCode, Integer dailyLimit,
                       String requiredMessage, String exhaustedMessage) {

    public static final String DEFAULT_REQUIRED_MESSAGE = "本站为受限体验，请先输入访问口令";
    public static final String DEFAULT_EXHAUSTED_MESSAGE = "今日体验轮次已用完，欢迎明天再来";

    /** 本地兜底初始值（yml 缺省口径）。 */
    public static GateRule localDefaults(boolean enabled, String accessCode, int dailyLimit,
                                         String requiredMessage, String exhaustedMessage) {
        return new GateRule(enabled, accessCode, dailyLimit, requiredMessage, exhaustedMessage);
    }

    /** 部分更新合并：null 字段沿用 fallback（当前生效值）。 */
    public GateRule normalize(GateRule fallback) {
        return new GateRule(
                enabled != null ? enabled : fallback.enabled(),
                accessCode != null ? accessCode : fallback.accessCode(),
                dailyLimit != null ? dailyLimit : fallback.dailyLimit(),
                requiredMessage != null ? requiredMessage : fallback.requiredMessage(),
                exhaustedMessage != null ? exhaustedMessage : fallback.exhaustedMessage());
    }

    /** 闸口是否生效（Boolean 拆箱安全）。 */
    public boolean active() {
        return Boolean.TRUE.equals(enabled);
    }
}
