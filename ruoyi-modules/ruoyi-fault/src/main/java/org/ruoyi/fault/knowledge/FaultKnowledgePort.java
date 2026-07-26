package org.ruoyi.fault.knowledge;

/**
 * 故障诊断访问外部知识手册的端口。
 */
public interface FaultKnowledgePort {

    /**
     * 按 Agent 已绑定的知识库范围查询故障码证据。
     */
    FaultKnowledgeResult query(FaultKnowledgeQuery query);
}
