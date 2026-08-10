package org.ruoyi.fault.telemetry.model;

import java.time.LocalDateTime;
import java.util.Map;

/** 面向内部工具的遥测指标统计结果，不包含原始时序记录。 */
public record TelemetryStatisticsResult(
    String deviceName,
    String inverterName,
    LocalDateTime windowStart,
    LocalDateTime windowEnd,
    int sampleCount,
    Map<String, Map<String, Number>> metrics,
    DataQualitySummary dataQuality
) {
}
