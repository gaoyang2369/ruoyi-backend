package org.ruoyi.fault.report;

import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.domain.result.DiagnosisResult;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Report V2 对诊断结果的轻量投影。
 *
 * <p>报告已在顶层提供数据质量、建议和证据，故这里不重复这些内部诊断内容。</p>
 */
public record DiagnosisSummary(
    DiagnosisStatus status,
    List<String> faultCodes,
    List<String> alarmCodes,
    List<String> unknownCodes,
    boolean partial,
    List<String> decisionRationale,
    List<CodeKnowledgeSummary> codeKnowledge
) {

    public DiagnosisSummary {
        faultCodes = faultCodes == null ? List.of() : List.copyOf(faultCodes);
        alarmCodes = alarmCodes == null ? List.of() : List.copyOf(alarmCodes);
        unknownCodes = unknownCodes == null ? List.of() : List.copyOf(unknownCodes);
        decisionRationale = decisionRationale == null ? List.of() : List.copyOf(decisionRationale);
        codeKnowledge = codeKnowledge == null ? List.of() : List.copyOf(codeKnowledge);
    }

    public static DiagnosisSummary from(DiagnosisResult diagnosis) {
        if (diagnosis == null) {
            return new DiagnosisSummary(DiagnosisStatus.DATA_INSUFFICIENT, List.of(), List.of(), List.of(), true,
                List.of(), List.of());
        }
        return new DiagnosisSummary(diagnosis.status(), diagnosis.faultCodes(), diagnosis.alarmCodes(),
            diagnosis.unknownCodes(), diagnosis.partial(), diagnosis.decisionRationale(),
            diagnosis.candidateFaults().stream().map(candidate -> new CodeKnowledgeSummary(
                candidate.faultCode(), candidate.codeType(), candidate.knowledgeStatus(),
                candidate.knowledgeEvidence().stream()
                    .map(evidence -> evidence.sourceDocument())
                    .filter(source -> source != null && !source.isBlank())
                    .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf))))
                .toList());
    }

    /** Markdown 所需的最小知识匹配投影，不携带知识正文或内部诊断对象。 */
    public record CodeKnowledgeSummary(String code, FaultCodeType codeType,
                                       KnowledgeLookupStatus knowledgeStatus, List<String> sourceDocuments) {
        public CodeKnowledgeSummary {
            sourceDocuments = sourceDocuments == null ? List.of() : List.copyOf(sourceDocuments);
        }
    }
}
