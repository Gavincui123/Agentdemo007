package com.agentdemo007.capability.business;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 用户查询服务（外部系统高置信数据·[[business-tools-workflow-dag]] §2.1）。
 *
 * <p>mock 实现：10086 = 当前账户 gold 会员（拥有 ORD-001/002），10010 = ORD-003 持有者（非当前账户）。
 * 与 {@link OrderQueryService} mock 数据一致（同一当前账户 10086，DAG validate 归属校验靠此对齐）——
 * 用户钦定"工具调用对应数据必须准确"：mock 须对得上前端真实用户 userId=10086/vipLevel=gold。
 * 后期接真用户系统换 impl 不换 seam。
 *
 * <p>收口：用户数据真相源只经此服务；未知/null 幂等返 empty 不抛（§5.12 每步降级）。
 */
@Component
public class UserQueryService {

    private static final Map<String, UserRecord> USERS = Map.of(
            "10086", new UserRecord("10086", "张三", "gold"),
            "10010", new UserRecord("10010", "李四", "silver"));

    /** 按用户 id 查询（外部系统高置信事实，将路由进 RunTime_* 通道）。 */
    public Optional<UserRecord> findByUserId(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(USERS.get(userId));
    }
}
