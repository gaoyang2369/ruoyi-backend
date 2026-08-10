package org.ruoyi.fault.telemetry.model;

/**
 * 运行报告一次遥测读取后的内部分析快照。
 * <p>
 * 三个结果由同一批原始记录、同一次业务时间过滤和去重口径产生；仅供报告编排使用，
 * 不作为新的 HTTP 接口响应。
 */
public record TelemetryReportSnapshot(
    TelemetryQueryResult telemetry,
    TelemetryStatisticsResult statistics,
    TelemetrySeriesResult series
) {
}
