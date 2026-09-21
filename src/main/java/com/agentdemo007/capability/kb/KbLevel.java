package com.agentdemo007.capability.kb;

/**
 * 客户等级可见性词表（Phase 21 三轴拆分·安全轴）。
 *
 * <p><b>有界业务词表进代码</b>（设计裁决 2026-09-20）：V0~V5 固定六档，权限主载体是文档属性
 * {@code kb_document.required_level}（NOT NULL DEFAULT 0），检索谓词 = 主体等级 ≥ 文档要求等级。
 * 与 {@link KbNamespace}（内外边界·主体类型轴）和 {@code domain}（业务分类·无界词表·DB）各归其轴；
 * {@code allowedPrincipals} 点对点白名单为例外通道，不承载主权限。
 *
 * <p>等级来源只有登录态 + 会员服务（{@code MemberLevelService}），<b>永不从对话内容取</b>——
 * 用户自称"我是VIP"不采信（提示词注入直通车）。查询失败/未知主体 fail-closed 按 V0 处理。
 */
public enum KbLevel {

    /** V0 公开：匿名/eval/未登录可见（fail-closed 缺省档）。 */
    V0("公开"),
    /** V1 注册客户。 */
    V1("注册客户"),
    /** V2 白银会员。 */
    V2("白银会员"),
    /** V3 黄金会员。 */
    V3("黄金会员"),
    /** V4 铂金会员。 */
    V4("铂金会员"),
    /** V5 全量：最高档（demo mock 10086）。 */
    V5("全量");

    private final String label;

    KbLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 档位值（对齐 DB required_level 列的 int 存储；ordinal 等价，显式命名防位次漂移）。 */
    public int code() {
        return ordinal();
    }

    /** 主体等级是否达到要求档（谓词核心比较：memberLevel.atLeast(requiredLevel)）。 */
    public boolean atLeast(KbLevel required) {
        return (required == null) || ordinal() >= required.ordinal();
    }

    /**
     * int 档位 → 枚举（快照加载边界）：越界/非法一律收敛 V0（fail-closed，坏数据不放大权限）。
     */
    public static KbLevel fromCode(int code) {
        KbLevel[] values = values();
        return (code >= 0 && code < values.length) ? values[code] : V0;
    }
}
