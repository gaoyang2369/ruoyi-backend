package org.ruoyi.fault.report;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.evidence.enums.EvidenceType;
import org.ruoyi.fault.telemetry.model.CodeOccurrence;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.OperationStatistics;
import org.ruoyi.fault.telemetry.model.StatusEvent;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetrySeriesResult;
import org.ruoyi.fault.telemetry.model.TelemetryStatisticsResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Report V2 的结构化运行报告。
 * <p>
 * 报告以本对象中的稳定业务结构为核心；Markdown、HTML 和 PDF 都只能消费该结构进行渲染，
 * 不得重新查询遥测或重新判断诊断结论。{@link #fromSources} 只负责把已有遥测、诊断结果投影为报告字段，
 * 不会保留遥测快照作为报告输出字段。
 */
public record OperationReportResult(
    Metadata metadata,
    Asset asset,
    Period period,
    ReportHealthStatus periodStatus,
    ReportHealthStatus currentStatus,
    Summary summary,
    DataQualitySummary dataQuality,
    Map<String, String> metricUnits,
    List<CompletenessCategory> dataCompleteness,
    List<Metric> metrics,
    List<Trend> trends,
    List<Event> events,
    List<StatusTimelineEvent> statusTimeline,
    DiagnosisSummary diagnosis,
    List<Recommendation> recommendations,
    List<Evidence> evidence,
    String narrative,
    List<String> limitations,
    @JsonIgnore DiagnosisResult diagnosisDetail
) {

    public static final String REPORT_TYPE = "OPERATION_REPORT_V2";

    public OperationReportResult {
        metricUnits = metricUnits == null ? Map.of() : Map.copyOf(metricUnits);
        dataCompleteness = dataCompleteness == null ? List.of() : List.copyOf(dataCompleteness);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        trends = trends == null ? List.of() : List.copyOf(trends);
        events = events == null ? List.of() : List.copyOf(events);
        statusTimeline = statusTimeline == null ? List.of() : List.copyOf(statusTimeline);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        narrative = narrative == null || narrative.isBlank() ? null : narrative.trim();
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    /** @deprecated 报告结构化输出请改用 {@link #periodStatus()}。 */
    @Deprecated
    @JsonIgnore
    public ReportHealthStatus overallStatus() {
        return periodStatus;
    }

    /** 将一次遥测报告快照中的通用统计和分桶结果投影为 Report V2 metrics 与 trends。 */
    public static OperationReportResult fromSources(String reportId, String deviceName, String inverterName,
                                                     LocalDateTime requestedStart, LocalDateTime requestedEnd,
                                                     LocalDateTime generatedAt, ReportHealthStatus periodStatus,
                                                     Summary summary, TelemetryQueryResult telemetry,
                                                     TelemetryStatisticsResult statistics,
                                                     TelemetrySeriesResult series, DiagnosisResult diagnosis) {
        return fromSources(reportId, deviceName, inverterName, requestedStart, requestedEnd, generatedAt,
            periodStatus, summary, telemetry, statistics, series, Map.of(), diagnosis);
    }

    public static OperationReportResult fromSources(String reportId, String deviceName, String inverterName,
                                                     LocalDateTime requestedStart, LocalDateTime requestedEnd,
                                                     LocalDateTime generatedAt, ReportHealthStatus periodStatus,
                                                     Summary summary, TelemetryQueryResult telemetry,
                                                     TelemetryStatisticsResult statistics,
                                                     TelemetrySeriesResult series,
                                                     Map<String, String> configuredMetricUnits,
                                                     DiagnosisResult diagnosis) {
        TelemetryQueryResult source = telemetry == null ? emptyTelemetry() : telemetry;
        List<Event> events = eventsOf(source.operation(), source.faultCodes(), source.alarmCodes(), source.statusEvents());
        List<Metric> metrics = metricsOf(statistics, source.operation());
        return new OperationReportResult(
            new Metadata(reportId, generatedAt, REPORT_TYPE),
            new Asset(deviceName, inverterName),
            new Period(requestedStart, requestedEnd, source.startTime(), source.endTime(),
                source.fallbackToLatestData(), source.latestObservedAt(), source.sourceDigest()),
            periodStatus,
            currentStatusOf(source, diagnosis, events),
            summary,
            source.quality(),
            metricUnitsOf(configuredMetricUnits, metrics),
            completenessOf(statistics, metrics),
            metrics,
            trendsOf(series),
            events,
            timelineOf(source.statusEvents()),
            DiagnosisSummary.from(diagnosis),
            recommendationsOf(diagnosis),
            evidenceOf(diagnosis),
            null,
            diagnosis == null ? List.of() : diagnosis.limitations(),
            diagnosis);
    }

    /** 将通过安全校验的模型叙事合并回同一份报告快照，不改变任何结构化事实。 */
    public OperationReportResult withNarrative(String narrative) {
        return new OperationReportResult(metadata, asset, period, periodStatus, currentStatus, summary,
            dataQuality, metricUnits, dataCompleteness, metrics, trends, events, statusTimeline, diagnosis,
            recommendations, evidence, narrative, limitations, diagnosisDetail);
    }

    /** 将后端配置中的单位冻结到报告快照；未配置的指标故意不补默认单位。 */
    private static Map<String, String> metricUnitsOf(Map<String, String> configuredUnits, List<Metric> metrics) {
        if (configuredUnits == null || configuredUnits.isEmpty() || metrics.isEmpty()) {
            return Map.of();
        }
        Map<String, String> units = new LinkedHashMap<>();
        for (Metric metric : metrics) {
            String unit = configuredUnits.get(metric.metricName());
            if (unit == null || unit.isBlank()) {
                unit = configuredUnits.get(metric.metricName().replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                    .toLowerCase(java.util.Locale.ROOT));
            }
            if (unit != null && !unit.isBlank()) {
                units.put(metric.metricName(), unit.trim());
            }
        }
        return Map.copyOf(units);
    }

    /** 指标完整率只由同一次统计快照中的真实非空计数计算，不从趋势点反推。 */
    private static List<CompletenessCategory> completenessOf(TelemetryStatisticsResult statistics,
                                                              List<Metric> metrics) {
        if (statistics == null || statistics.sampleCount() <= 0 || metrics.isEmpty()) {
            return List.of();
        }
        int expected = statistics.sampleCount();
        return metrics.stream()
            .filter(metric -> metric.count() != null)
            .map(metric -> {
                int actual = Math.max(0, Math.min(expected, metric.count()));
                return new CompletenessCategory(metric.metricName(), expected, actual,
                    (double) actual / expected);
            })
            .toList();
    }

    private static ReportHealthStatus currentStatusOf(TelemetryQueryResult telemetry, DiagnosisResult diagnosis,
                                                      List<Event> events) {
        if (telemetry.fallbackToLatestData() || diagnosis == null
            || diagnosis.status() == org.ruoyi.fault.domain.enums.DiagnosisStatus.DATA_INSUFFICIENT) {
            return ReportHealthStatus.UNKNOWN;
        }
        if (events.stream().anyMatch(event -> event.active() && event.type() == FaultCodeType.FAULT)) {
            return ReportHealthStatus.FAULT;
        }
        if (events.stream().anyMatch(event -> event.active() && event.type() == FaultCodeType.ALARM)) {
            return ReportHealthStatus.ATTENTION;
        }
        return ReportHealthStatus.NORMAL;
    }

    private static TelemetryQueryResult emptyTelemetry() {
        return new TelemetryQueryResult(null, null, null, null, List.of(), List.of(), List.of(), List.of(),
            null, null, false, null, List.of(), null);
    }

    private static List<Metric> metricsOf(TelemetryStatisticsResult statistics, OperationStatistics operation) {
        if (statistics == null || statistics.metrics() == null) {
            return List.of();
        }
        return statistics.metrics().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).map(entry -> {
            java.util.Map<String, Number> values = entry.getValue();
            return new Metric(entry.getKey(), null, numberOf(values, "avg"), numberOf(values, "min"),
                numberOf(values, "max"), integerOf(values, "count"), peakAt(entry.getKey(), operation));
        }).toList();
    }

    /** 仅附加既有运行摘要已计算出的峰值时刻，不在报告层重新扫描遥测数据。 */
    private static LocalDateTime peakAt(String metricName, OperationStatistics operation) {
        if (operation == null) {
            return null;
        }
        return switch (metricName) {
            case "motorTemp" -> operation.maxMotorTempAt();
            case "motorLoadRate" -> operation.maxMotorLoadRateAt();
            default -> null;
        };
    }

    private static List<Trend> trendsOf(TelemetrySeriesResult series) {
        if (series == null || series.series() == null) {
            return List.of();
        }
        return series.series().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
            .map(entry -> new Trend(entry.getKey(), entry.getValue().stream()
                .map(point -> new TrendPoint(point.timestamp(), point.value(), point.count())).toList()))
            .toList();
    }

    private static Double numberOf(java.util.Map<String, Number> values, String name) {
        Number value = values == null ? null : values.get(name);
        return value == null ? null : value.doubleValue();
    }

    private static Integer integerOf(java.util.Map<String, Number> values, String name) {
        Number value = values == null ? null : values.get(name);
        return value == null ? null : value.intValue();
    }

    private static List<Event> eventsOf(OperationStatistics operation, List<String> faultCodes,
                                        List<String> alarmCodes, List<StatusEvent> timeline) {
        List<Event> events = new ArrayList<>();
        if (operation != null) {
            appendEvents(events, operation.faultCodeOccurrences(), FaultCodeType.FAULT, timeline);
            appendEvents(events, operation.alarmCodeOccurrences(), FaultCodeType.ALARM, timeline);
        }
        appendMissingCodes(events, faultCodes, FaultCodeType.FAULT);
        appendMissingCodes(events, alarmCodes, FaultCodeType.ALARM);
        return List.copyOf(events);
    }

    private static void appendEvents(List<Event> target, List<CodeOccurrence> occurrences, FaultCodeType type,
                                     List<StatusEvent> timeline) {
        if (occurrences == null) {
            return;
        }
        for (CodeOccurrence occurrence : occurrences) {
            boolean active = occurrence.active() || activeAtWindowEnd(occurrence.code(), type, timeline);
            LocalDateTime recoveredAt = occurrence.recoveredAt() == null && !active
                ? recoveredAt(occurrence.code(), type, occurrence.lastObservedAt(), timeline) : occurrence.recoveredAt();
            target.add(new Event(occurrence.code(), type, active, occurrence.firstObservedAt(),
                occurrence.lastObservedAt(), recoveredAt, occurrence.sampleCount()));
        }
    }

    private static void appendMissingCodes(List<Event> target, List<String> codes, FaultCodeType type) {
        if (codes == null) {
            return;
        }
        for (String code : codes) {
            boolean present = target.stream().anyMatch(event -> event.type() == type && code.equals(event.code()));
            if (!present) {
                target.add(new Event(code, type, false, null, null, null, 0));
            }
        }
    }

    private static boolean activeAtWindowEnd(String code, FaultCodeType type, List<StatusEvent> timeline) {
        if (timeline == null || timeline.isEmpty()) {
            return false;
        }
        StatusEvent last = timeline.get(timeline.size() - 1);
        return code.equals(type == FaultCodeType.FAULT ? last.faultCode() : last.alarmCode());
    }

    private static LocalDateTime recoveredAt(String code, FaultCodeType type, LocalDateTime lastSeenAt,
                                             List<StatusEvent> timeline) {
        if (timeline == null) {
            return null;
        }
        for (StatusEvent event : timeline) {
            if (lastSeenAt != null && event.observedAt() != null && event.observedAt().isBefore(lastSeenAt)) {
                continue;
            }
            String eventCode = type == FaultCodeType.FAULT ? event.faultCode() : event.alarmCode();
            if (!code.equals(eventCode) && event.observedAt() != null) {
                return event.observedAt();
            }
        }
        return null;
    }

    private static List<StatusTimelineEvent> timelineOf(List<StatusEvent> events) {
        if (events == null) {
            return List.of();
        }
        return events.stream().map(event -> new StatusTimelineEvent(event.observedAt(), event.status(),
            event.faultCode(), event.alarmCode())).toList();
    }

    private static List<Recommendation> recommendationsOf(DiagnosisResult diagnosis) {
        if (diagnosis == null) {
            return List.of();
        }
        return diagnosis.recommendations().stream()
            .map(content -> new Recommendation(content, "diagnosis.recommendations")).toList();
    }

    private static List<Evidence> evidenceOf(DiagnosisResult diagnosis) {
        if (diagnosis == null) {
            return List.of();
        }
        return diagnosis.evidenceIndex().stream().map(OperationReportResult::toEvidence).toList();
    }

    private static Evidence toEvidence(EvidenceReference reference) {
        return new Evidence(reference.evidenceId(), reference.evidenceCode(), reference.evidenceType(),
            reference.title(), reference.summary(), reference.userVisible());
    }

    public record Metadata(String reportId, LocalDateTime generatedAt, String reportType) {
    }

    public record Asset(String deviceName, String inverterName) {
    }

    /** 请求窗口与实际分析窗口同时保留，回退时不会将历史窗口误称为请求窗口。 */
    public record Period(LocalDateTime windowStart, LocalDateTime windowEnd,
                         LocalDateTime analysisWindowStart, LocalDateTime analysisWindowEnd,
                         boolean fallbackToLatestData, LocalDateTime latestObservedAt, String sourceDigest) {
    }

    /** 简短结论及其结构化代码清单；conclusion 是普通文本，不是 Markdown 文档。 */
    public record Summary(String conclusion, List<String> faultCodes, List<String> alarmCodes,
                          boolean currentStatusConfirmed) {
        public Summary {
            faultCodes = faultCodes == null ? List.of() : List.copyOf(faultCodes);
            alarmCodes = alarmCodes == null ? List.of() : List.copyOf(alarmCodes);
        }
    }

    /** 不填单位，避免在没有可靠配置时臆测单位。 */
    public record Metric(String metricName, Double current, Double average, Double minimum, Double maximum,
                         Integer count, LocalDateTime peakAt) {
    }

    /** 单个指标按既有遥测 series 分桶计算的事实趋势，不包含预测或趋势判断。 */
    public record Trend(String metricName, List<TrendPoint> points) {
        public Trend {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    public record TrendPoint(LocalDateTime timestamp, Double value, long count) {
    }

    /** 单个真实遥测指标在有效样本中的非空覆盖率。 */
    public record CompletenessCategory(String categoryName, int expectedCount, int actualCount,
                                       double completeness) {
    }

    /** 一个故障码或报警码在窗口内的聚合事件；sampleHitCount 是包含该代码的遥测样本数。 */
    public record Event(String code, FaultCodeType type, boolean active, LocalDateTime firstSeenAt,
                        LocalDateTime lastSeenAt, LocalDateTime recoveredAt, int sampleHitCount) {
    }

    /** 供 Markdown 等时间线渲染器使用的既有状态变化事实。 */
    public record StatusTimelineEvent(LocalDateTime observedAt, String status, String faultCode, String alarmCode) {
    }

    /** 建议只转存既有诊断建议及其来源，不在报告层生成新建议。 */
    public record Recommendation(String content, String source) {
    }

    /** 持久化证据的结构化展示信息。 */
    public record Evidence(Long evidenceId, String evidenceCode, EvidenceType type, String source,
                           String content, boolean userVisible) {
    }
}
