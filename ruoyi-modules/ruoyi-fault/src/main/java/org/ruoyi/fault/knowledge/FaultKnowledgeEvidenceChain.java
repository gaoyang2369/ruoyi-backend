package org.ruoyi.fault.knowledge;

import java.util.List;

/**
 * 单次故障知识查询的内存检索轨迹：原始请求与全部候选片段的摘要。
 * <p>当前不负责数据库持久化；完整诊断流程接入后再统一持久化。</p>
 */
public record FaultKnowledgeEvidenceChain(
    FaultKnowledgeQuery query,
    List<FaultKnowledgeRetrievalTrace> retrievals
) {
    public FaultKnowledgeEvidenceChain {
        retrievals = List.copyOf(retrievals);
    }
}
