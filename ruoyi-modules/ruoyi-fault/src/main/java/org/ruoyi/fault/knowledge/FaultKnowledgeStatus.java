package org.ruoyi.fault.knowledge;

/** 故障知识查询的确定性状态。 */
public enum FaultKnowledgeStatus {
    /** 至少存在一个可追溯的精确故障码证据。 */
    MATCHED,
    /** 至少一个知识库成功检索，但没有精确故障码证据。 */
    NOT_FOUND,
    /** 所有知识库检索均失败，无法判断故障码是否存在。 */
    FAILED
}
