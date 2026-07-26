package org.ruoyi.fault.knowledge;

import java.util.List;

/**
 * 故障知识检索的可审计证据链：原始请求与全部候选片段的摘要。
 */
public record FaultKnowledgeEvidenceChain(
    FaultKnowledgeQuery query,
    List<FaultKnowledgeRetrievalTrace> retrievals
) {
    public FaultKnowledgeEvidenceChain {
        retrievals = List.copyOf(retrievals);
    }
}
