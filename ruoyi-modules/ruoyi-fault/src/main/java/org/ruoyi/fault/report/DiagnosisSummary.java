package org.ruoyi.fault.report;

import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.result.DiagnosisResult;

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
    List<String> decisionRationale
) {

    public DiagnosisSummary {
        faultCodes = faultCodes == null ? List.of() : List.copyOf(faultCodes);
        alarmCodes = alarmCodes == null ? List.of() : List.copyOf(alarmCodes);
        unknownCodes = unknownCodes == null ? List.of() : List.copyOf(unknownCodes);
        decisionRationale = decisionRationale == null ? List.of() : List.copyOf(decisionRationale);
    }

    public static DiagnosisSummary from(DiagnosisResult diagnosis) {
        if (diagnosis == null) {
            return new DiagnosisSummary(DiagnosisStatus.DATA_INSUFFICIENT, List.of(), List.of(), List.of(), true, List.of());
        }
        return new DiagnosisSummary(diagnosis.status(), diagnosis.faultCodes(), diagnosis.alarmCodes(),
            diagnosis.unknownCodes(), diagnosis.partial(), diagnosis.decisionRationale());
    }
}
