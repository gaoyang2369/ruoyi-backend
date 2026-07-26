package org.ruoyi.fault.domain.result;

import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;

import java.util.List;

/** 仅由遥测中的显式故障码产生的候选故障。 */
public record CandidateFault(
    String faultCode,
    KnowledgeLookupStatus knowledgeStatus,
    List<FaultKnowledgeEvidence> knowledgeEvidence,
    List<String> evidenceCodes
) {
    public CandidateFault {
        knowledgeEvidence = knowledgeEvidence == null ? List.of() : List.copyOf(knowledgeEvidence);
        evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
    }
}
