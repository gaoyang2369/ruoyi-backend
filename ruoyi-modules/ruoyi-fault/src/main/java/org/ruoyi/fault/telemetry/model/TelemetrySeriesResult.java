package org.ruoyi.fault.telemetry.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 面向内部工具的降采样遥测时序结果，不包含原始遥测记录。 */
public record TelemetrySeriesResult(
    String deviceName,
    String inverterName,
    LocalDateTime windowStart,
    LocalDateTime windowEnd,
    int bucketMinutes,
    int sampleCount,
    Map<String, List<TelemetrySeriesPoint>> series,
    DataQualitySummary dataQuality
) {
}
