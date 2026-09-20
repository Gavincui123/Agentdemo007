package com.agentdemo007.capability.kb;

/**
 * 知识库命名空间（[[kb-ingest-design]]·任务3 元数据）。
 *
 * <p>区分公开与私有知识：PUBLIC 所有人（含匿名对话）可检索；PRIVATE 仅
 * {@code allowedPrincipals} 名单内的主体（对话侧取 {@code PipelineContext.userId}，
 * 管理侧恒可见）可检索。namespace + docNo 构成文档业务键（版本在其上自增）。
 */
public enum KbNamespace {

    /** 公开知识：所有人可检索（客服标准口径）。 */
    PUBLIC("公开"),
    /** 私有知识：仅 allowedPrincipals 名单主体可检索（内部口径/待发布/专属客群）。 */
    PRIVATE("私有");

    private final String label;

    KbNamespace(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
