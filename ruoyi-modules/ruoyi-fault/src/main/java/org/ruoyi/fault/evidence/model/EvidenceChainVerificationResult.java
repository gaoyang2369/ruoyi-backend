package org.ruoyi.fault.evidence.model;

/** 证据链完整性校验结果。 */
public record EvidenceChainVerificationResult(
    boolean valid,
    Long caseId,
    int expectedCount,
    int actualCount,
    Integer failedSequence,
    String reason
) {
}
