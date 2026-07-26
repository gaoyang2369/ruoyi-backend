package org.ruoyi.fault.knowledge;

import java.util.List;

/**
 * 故障知识查询结果。仅精确命中的片段出现在 evidence 中。
 */
public record FaultKnowledgeResult(
    Status status,
    String faultCode,
    List<FaultKnowledgeEvidence> evidence,
    String errorCode,
    String errorMessage
) {
    public FaultKnowledgeResult {
        evidence = List.copyOf(evidence);
        if ((status == Status.MATCHED) != !evidence.isEmpty()) {
            throw new IllegalArgumentException("MATCHED状态必须与正式故障知识证据一致");
        }
    }

    public boolean matched() {
        return status == Status.MATCHED;
    }

    public static FaultKnowledgeResult matched(FaultKnowledgeQuery query, List<FaultKnowledgeEvidence> evidence) {
        return new FaultKnowledgeResult(Status.MATCHED, query.faultCode(), evidence, null, null);
    }

    public static FaultKnowledgeResult notFound(FaultKnowledgeQuery query) {
        return new FaultKnowledgeResult(Status.NOT_FOUND, query.faultCode(), List.of(), null, null);
    }

    public static FaultKnowledgeResult failed(FaultKnowledgeQuery query) {
        return new FaultKnowledgeResult(Status.FAILED, query.faultCode(), List.of(),
            "FAULT_KNOWLEDGE_RETRIEVAL_FAILED", "故障知识查询暂不可用，请稍后重试");
    }

    /** 故障知识查询的确定性状态。 */
    public enum Status {
        /** 至少存在一个精确故障码证据。 */
        MATCHED,
        /** 至少一个知识库成功检索，但没有精确故障码证据。 */
        NOT_FOUND,
        /** 所有知识库检索均失败，无法判断故障码是否存在。 */
        FAILED
    }
}
