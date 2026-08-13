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
    TelemetrySeriesResult series,
    java.util.List<ReportTelemetrySample> analysisSamples
) {

    public TelemetryReportSnapshot {
        analysisSamples = analysisSamples == null ? java.util.List.of() : java.util.List.copyOf(analysisSamples);
    }

    /** 兼容仅包含既有三个报告投影的调用方。 */
    public TelemetryReportSnapshot(TelemetryQueryResult telemetry, TelemetryStatisticsResult statistics,
                                   TelemetrySeriesResult series) {
        this(telemetry, statistics, series, java.util.List.of());
    }
}
