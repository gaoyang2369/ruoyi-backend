package org.ruoyi.fault.domain.result;

import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.TelemetryStatistics;

import java.time.LocalDateTime;
import java.util.List;

/** 对外的不可变诊断结果，不包含聊天文本或原始时序数据。 */
public record DiagnosisResult(
    String requestId,
    DiagnosisStatus status,
    boolean partial,
    String deviceName,
    String inverterName,
    LocalDateTime startTime,
    LocalDateTime endTime,
    String symptom,
    DataQualitySummary dataQuality,
    TelemetryStatistics statistics,
    List<String> faultCodes,
    List<String> alarmCodes,
    List<DiagnosisObservation> observations,
    List<CandidateFault> candidateFaults,
    List<String> recommendations,
    List<String> limitations,
    List<EvidenceReference> evidenceIndex
) {
    public DiagnosisResult {
        faultCodes = faultCodes == null ? List.of() : List.copyOf(faultCodes);
        alarmCodes = alarmCodes == null ? List.of() : List.copyOf(alarmCodes);
        observations = observations == null ? List.of() : List.copyOf(observations);
        candidateFaults = candidateFaults == null ? List.of() : List.copyOf(candidateFaults);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        evidenceIndex = evidenceIndex == null ? List.of() : List.copyOf(evidenceIndex);
    }

    public DiagnosisResult withEvidenceIndex(List<EvidenceReference> references, boolean partialResult,
                                             List<String> resultLimitations) {
        return new DiagnosisResult(requestId, status, partial || partialResult, deviceName, inverterName, startTime,
            endTime, symptom, dataQuality, statistics, faultCodes, alarmCodes, observations, candidateFaults,
            recommendations, resultLimitations, references);
    }
}
