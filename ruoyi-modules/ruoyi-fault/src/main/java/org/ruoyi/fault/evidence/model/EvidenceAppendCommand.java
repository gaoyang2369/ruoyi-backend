package org.ruoyi.fault.evidence.model;

import org.ruoyi.fault.evidence.enums.EvidenceType;

import java.math.BigDecimal;

/** 追加一条证据的输入。 */
public record EvidenceAppendCommand(
    Long caseId,
    Long stepId,
    EvidenceType evidenceType,
    String sourceSystem,
    String sourceReference,
    Object requestPayload,
    Object resultSummary,
    Integer sourceRecordCount,
    String sourceDigest,
    BigDecimal qualityScore
) {
}
