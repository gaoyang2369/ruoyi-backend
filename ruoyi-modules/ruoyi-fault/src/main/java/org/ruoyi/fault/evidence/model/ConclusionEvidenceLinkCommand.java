package org.ruoyi.fault.evidence.model;

import org.ruoyi.fault.evidence.enums.EvidenceRelationType;

import java.math.BigDecimal;

/** 将已有证据关联至结论的输入。 */
public record ConclusionEvidenceLinkCommand(
    Long caseId,
    String conclusionCode,
    Long evidenceId,
    EvidenceRelationType relationType,
    BigDecimal weight
) {
}
