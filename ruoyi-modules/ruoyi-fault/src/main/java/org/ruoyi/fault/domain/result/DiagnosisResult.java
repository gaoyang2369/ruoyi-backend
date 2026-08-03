package org.ruoyi.fault.domain.result;

import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.TelemetryStatistics;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对外的不可变诊断结果，不包含聊天文本或原始时序数据。
 * <p>
 * 时间边界为结构化字段，回答层必须据此决定时态，不得从限制说明文本中推断：
 * {@code requestedStartTime/requestedEndTime} 是用户请求窗口，
 * {@code startTime/endTime} 是实际分析窗口（回退时为最近可用数据窗口），
 * {@code fallbackToLatestData} 标记是否发生历史回退，
 * {@code latestObservedAt} 是最后一条有效遥测的业务时间。
 */
public record DiagnosisResult(
    String requestId,
    DiagnosisStatus status,
    boolean partial,
    String deviceName,
    String inverterName,
    LocalDateTime requestedStartTime,
    LocalDateTime requestedEndTime,
    LocalDateTime startTime,
    LocalDateTime endTime,
    boolean fallbackToLatestData,
    LocalDateTime latestObservedAt,
    String symptom,
    DataQualitySummary dataQuality,
    TelemetryStatistics statistics,
    List<String> faultCodes,
    List<String> alarmCodes,
    List<String> unknownCodes,
    List<DiagnosisObservation> observations,
    List<CandidateFault> candidateFaults,
    List<String> recommendations,
    List<String> limitations,
    List<EvidenceReference> evidenceIndex
) {
    public DiagnosisResult {
        faultCodes = faultCodes == null ? List.of() : List.copyOf(faultCodes);
        alarmCodes = alarmCodes == null ? List.of() : List.copyOf(alarmCodes);
        unknownCodes = unknownCodes == null ? List.of() : List.copyOf(unknownCodes);
        observations = observations == null ? List.of() : List.copyOf(observations);
        candidateFaults = candidateFaults == null ? List.of() : List.copyOf(candidateFaults);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        evidenceIndex = evidenceIndex == null ? List.of() : List.copyOf(evidenceIndex);
    }

    public DiagnosisResult withEvidenceIndex(List<EvidenceReference> references, boolean partialResult,
                                             List<String> resultLimitations) {
        return new DiagnosisResult(requestId, status, partial || partialResult, deviceName, inverterName,
            requestedStartTime, requestedEndTime, startTime, endTime, fallbackToLatestData, latestObservedAt,
            symptom, dataQuality, statistics, faultCodes, alarmCodes, unknownCodes, observations, candidateFaults,
            recommendations, resultLimitations, references);
    }
}
