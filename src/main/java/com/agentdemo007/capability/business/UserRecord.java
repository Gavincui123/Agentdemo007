package com.agentdemo007.capability.business;

/**
 * 用户记录（外部系统高置信数据 carrier·[[business-tools-workflow-dag]] §2.1）。
 *
 * <p>tier 供 T3 商品推荐场景判断活动/会员资格（gold→会员价/活动资格；用户钦定 vipLevel=gold）。
 */
public record UserRecord(String userId, String name, String tier) {
}
