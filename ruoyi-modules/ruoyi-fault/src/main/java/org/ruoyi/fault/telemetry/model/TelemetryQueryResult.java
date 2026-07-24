package org.ruoyi.fault.telemetry.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 固定遥测查询的聚合结果，不包含原始时序行。
 */
public record TelemetryQueryResult(
    String assetCode,
    LocalDateTime startTime,
    LocalDateTime endTime,
    DataQualitySummary quality,
    List<String> faultCodes,
    List<String> alarmCodes,
    List<StatusEvent> statusEvents,
    TelemetryStatistics statistics,
    String sourceDigest
) {
}
