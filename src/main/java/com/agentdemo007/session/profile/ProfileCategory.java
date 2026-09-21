package com.agentdemo007.session.profile;

/**
 * 用户画像白名单字段（Phase 22·T102 三类，全部"业务系统查不到、只影响表达不影响能力"）。
 *
 * <p>白名单进代码（有界词表原则）：画像只允许这三类，LLM 提议的类别越界即拒。
 * 画像永不承载权限语义（等级唯一来源=会员服务，"记住我是 VIP"被守门拒绝），
 * 也不承载业务实体（订单号归 L0 注册表与业务库）。
 */
public enum ProfileCategory {

    /** 偏好：如"喜欢简洁回复""偏好文字而非电话"。 */
    PREFERENCE("偏好"),

    /** 硬约束：如"不接收电话回访""不需要节日营销"。 */
    CONSTRAINT("硬约束"),

    /** 沟通风格：如"希望称呼'您'""习惯分点说明"。 */
    STYLE("沟通风格");

    private final String label;

    ProfileCategory(String label) {
        this.label = label;
    }

    /** 中文标签（System 运行时块渲染用）。 */
    public String label() {
        return label;
    }

    /** LLM 提议的类别名 → 枚举；未知/null → empty（守门拒绝的第一道）。 */
    public static java.util.Optional<ProfileCategory> fromName(String name) {
        if (name == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
