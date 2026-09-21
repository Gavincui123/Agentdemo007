package com.agentdemo007.session;

import com.agentdemo007.capability.kb.KbLevel;

/**
 * 会员等级解析 seam（Phase 21 客户等级可见性·安全轴）。
 *
 * <p>等级唯一来源 = 登态 uid + 会员服务查询；<b>永不从对话内容取</b>——用户自称
 * "我是VIP"不采信（提示词注入直通车）。失败语义 fail-closed：查不到/服务挂/uid 为空
 * 一律返回 {@link KbLevel#V0}（只出 PUBLIC 级知识），实现<b>不得抛异常、不得返回 null</b>
 * （调用方零防御，等级解析失败绝不阻塞主链）。
 *
 * <p>prod 覆盖：装配真实会员服务实现（注册同类型 bean 即替换 mock，见 MemberLevelConfig）。
 */
public interface MemberLevelService {

    /** 解析主体客户等级；任何失败路径收敛 V0（不抛异常、不返回 null）。 */
    KbLevel levelOf(String userId);
}
