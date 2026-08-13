package org.ruoyi.fault.report;

import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.domain.result.DiagnosisResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    List<CodeKnowledgeSummary> codeKnowledge,
    List<String> allowedTechnicalTokens
) {

    private static final Pattern TECHNICAL_TOKEN =
        Pattern.compile("(?i)(?<![A-Z0-9_])([pr]\\d+(?:\\.\\d+)?)(?![A-Z0-9_])");

    public DiagnosisSummary {
        faultCodes = faultCodes == null ? List.of() : List.copyOf(faultCodes);
        alarmCodes = alarmCodes == null ? List.of() : List.copyOf(alarmCodes);
        unknownCodes = unknownCodes == null ? List.of() : List.copyOf(unknownCodes);
        decisionRationale = decisionRationale == null ? List.of() : List.copyOf(decisionRationale);
        codeKnowledge = codeKnowledge == null ? List.of() : List.copyOf(codeKnowledge);
        allowedTechnicalTokens = allowedTechnicalTokens == null ? List.of() : allowedTechnicalTokens.stream()
            .filter(value -> value != null && !value.isBlank()).map(value -> value.trim().toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
    }

    /** 兼容既有 Report V2 快照；旧快照没有可持久化的参数白名单。 */
    public DiagnosisSummary(DiagnosisStatus status, List<String> faultCodes, List<String> alarmCodes,
                            List<String> unknownCodes, boolean partial, List<String> decisionRationale,
                            List<CodeKnowledgeSummary> codeKnowledge) {
        this(status, faultCodes, alarmCodes, unknownCodes, partial, decisionRationale, codeKnowledge, List.of());
    }

    public static DiagnosisSummary from(DiagnosisResult diagnosis) {
        if (diagnosis == null) {
            return new DiagnosisSummary(DiagnosisStatus.DATA_INSUFFICIENT, List.of(), List.of(), List.of(), true,
                List.of(), List.of(), List.of());
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
                .toList(), technicalTokens(diagnosis));
    }

    /**
     * finalize 读取的是 JSON 快照，不能再依赖 {@code DiagnosisResult} 中被忽略的原始知识对象。
     * 因此在 prepare 时冻结本次手册片段实际出现过的参数 token，供后续叙事校验使用。
     */
    private static List<String> technicalTokens(DiagnosisResult diagnosis) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        diagnosis.candidateFaults().forEach(candidate -> candidate.knowledgeEvidence().forEach(evidence -> {
            if (evidence == null || evidence.content() == null) return;
            Matcher matcher = TECHNICAL_TOKEN.matcher(evidence.content());
            while (matcher.find()) result.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }));
        return List.copyOf(result);
    }

    /** Markdown 所需的最小知识匹配投影，不携带知识正文或内部诊断对象。 */
    public record CodeKnowledgeSummary(String code, FaultCodeType codeType,
                                       KnowledgeLookupStatus knowledgeStatus, List<String> sourceDocuments) {
        public CodeKnowledgeSummary {
            sourceDocuments = sourceDocuments == null ? List.of() : List.copyOf(sourceDocuments);
        }
    }
}
