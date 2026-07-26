package org.ruoyi.fault.knowledge;

import java.util.List;

/**
 * 故障知识查询结果。仅 exactCodeMatched=true 的片段出现在 evidence 中。
 */
public record FaultKnowledgeResult(
    boolean matched,
    String faultCode,
    List<FaultKnowledgeEvidence> evidence,
    FaultKnowledgeEvidenceChain evidenceChain
) {
    public FaultKnowledgeResult {
        evidence = List.copyOf(evidence);
        if (matched != !evidence.isEmpty()) {
            throw new IllegalArgumentException("matched必须与正式故障知识证据一致");
        }
    }

    public static FaultKnowledgeResult unmatched(FaultKnowledgeQuery query,
                                                  List<FaultKnowledgeRetrievalTrace> retrievals) {
        return new FaultKnowledgeResult(false, query.faultCode(), List.of(),
            new FaultKnowledgeEvidenceChain(query, retrievals));
    }
}
