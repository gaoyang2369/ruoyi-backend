package org.ruoyi.fault.domain.result;

import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;

import java.util.List;

/**
 * 遥测中显式观测到、并进入知识查询的候选代码（故障码或报警码）。
 *
 * @param faultCode 观测到的代码
 * @param codeType G120 代码类型；决定回答中使用“故障”还是“报警”措辞
 * @param knowledgeStatus 知识查询状态
 * @param knowledgeEvidence 精确命中的知识片段
 * @param evidenceCodes 本次知识查询对应的持久化证据编号
 */
public record CandidateFault(
    String faultCode,
    FaultCodeType codeType,
    KnowledgeLookupStatus knowledgeStatus,
    List<FaultKnowledgeEvidence> knowledgeEvidence,
    List<String> evidenceCodes
) {
    public CandidateFault {
        codeType = codeType == null ? FaultCodeType.FAULT : codeType;
        knowledgeEvidence = knowledgeEvidence == null ? List.of() : List.copyOf(knowledgeEvidence);
        evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
    }
}
