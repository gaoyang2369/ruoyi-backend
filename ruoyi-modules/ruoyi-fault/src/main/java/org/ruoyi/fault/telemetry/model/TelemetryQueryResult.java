package org.ruoyi.fault.telemetry.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 固定遥测查询的聚合结果，不包含原始时序行。
 *
 * @param assetCode 已通过权限校验的资产编码
 * @param startTime 实际分析窗口起点（包含）；未回退时与请求窗口一致
 * @param endTime 实际分析窗口终点（不包含）；未回退时与请求窗口一致
 * @param quality 数据质量摘要
 * @param faultCodes 窗口内按 G120 规则归类为故障（F 类）的去重代码
 * @param alarmCodes 窗口内按 G120 规则归类为报警（A 类）的去重代码
 * @param unknownCodes 窗口内无法按 G120 规则识别的代码；不升级为故障
 * @param statusEvents 状态、故障码或报警码发生变化时的关键事件
 * @param statistics 关键数值指标统计
 * @param sourceDigest 覆盖查询条件与后端证据行的 SHA-256 摘要
 * @param fallbackToLatestData 请求窗口无数据时是否回退到了最近可用数据窗口
 * @param latestObservedAt 最后一条有效遥测的业务时间；无有效记录时为 null
 * @param codeNormalizationNotes 代码归一化过程中发现的数据质量问题说明
 * @param operation 运行报告级统计量；仅诊断用途的旧调用方可传 null
 */
public record TelemetryQueryResult(
    String assetCode,
    LocalDateTime startTime,
    LocalDateTime endTime,
    DataQualitySummary quality,
    List<String> faultCodes,
    List<String> alarmCodes,
    List<String> unknownCodes,
    List<StatusEvent> statusEvents,
    TelemetryStatistics statistics,
    String sourceDigest,
    boolean fallbackToLatestData,
    LocalDateTime latestObservedAt,
    List<String> codeNormalizationNotes,
    OperationStatistics operation
) {

    public TelemetryQueryResult {
        faultCodes = faultCodes == null ? List.of() : List.copyOf(faultCodes);
        alarmCodes = alarmCodes == null ? List.of() : List.copyOf(alarmCodes);
        unknownCodes = unknownCodes == null ? List.of() : List.copyOf(unknownCodes);
        statusEvents = statusEvents == null ? List.of() : List.copyOf(statusEvents);
        codeNormalizationNotes = codeNormalizationNotes == null ? List.of() : List.copyOf(codeNormalizationNotes);
    }

    /** 返回仅切换回退标记、其余字段不变的副本。 */
    public TelemetryQueryResult withFallbackToLatestData(boolean fallback) {
        return new TelemetryQueryResult(assetCode, startTime, endTime, quality, faultCodes, alarmCodes,
            unknownCodes, statusEvents, statistics, sourceDigest, fallback, latestObservedAt,
            codeNormalizationNotes, operation);
    }

}
