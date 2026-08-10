package org.ruoyi.fault.telemetry.model;

import java.time.LocalDateTime;

/** 单个遥测指标在一个时间桶内的平均值。 */
public record TelemetrySeriesPoint(
    LocalDateTime timestamp,
    Double value,
    long count
) {
}
