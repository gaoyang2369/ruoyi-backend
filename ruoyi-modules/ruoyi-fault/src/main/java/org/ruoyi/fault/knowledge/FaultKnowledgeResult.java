package org.ruoyi.fault.knowledge;

import java.util.List;

/**
 * 故障知识查询结果。仅精确命中的片段出现在 evidence 中。
 */
public record FaultKnowledgeResult(
    FaultKnowledgeStatus status,
    String faultCode,
    List<FaultKnowledgeEvidence> evidence,
    FaultKnowledgeEvidenceChain evidenceChain,
    String errorCode,
    String errorMessage
) {
    public FaultKnowledgeResult {
        evidence = List.copyOf(evidence);
        if ((status == FaultKnowledgeStatus.MATCHED) != !evidence.isEmpty()) {
            throw new IllegalArgumentException("MATCHED状态必须与正式故障知识证据一致");
        }
    }

    public boolean matched() {
        return status == FaultKnowledgeStatus.MATCHED;
    }

    public static FaultKnowledgeResult matched(FaultKnowledgeQuery query, List<FaultKnowledgeEvidence> evidence,
                                               List<FaultKnowledgeRetrievalTrace> retrievals) {
        return new FaultKnowledgeResult(FaultKnowledgeStatus.MATCHED, query.faultCode(), evidence,
            new FaultKnowledgeEvidenceChain(query, retrievals), null, null);
    }

    public static FaultKnowledgeResult notFound(FaultKnowledgeQuery query,
                                                List<FaultKnowledgeRetrievalTrace> retrievals) {
        return new FaultKnowledgeResult(FaultKnowledgeStatus.NOT_FOUND, query.faultCode(), List.of(),
            new FaultKnowledgeEvidenceChain(query, retrievals), null, null);
    }

    public static FaultKnowledgeResult failed(FaultKnowledgeQuery query,
                                              List<FaultKnowledgeRetrievalTrace> retrievals) {
        return new FaultKnowledgeResult(FaultKnowledgeStatus.FAILED, query.faultCode(), List.of(),
            new FaultKnowledgeEvidenceChain(query, retrievals),
            "FAULT_KNOWLEDGE_RETRIEVAL_FAILED", "故障知识查询暂不可用，请稍后重试");
    }
}
