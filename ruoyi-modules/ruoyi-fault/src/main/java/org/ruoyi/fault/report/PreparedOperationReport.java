package org.ruoyi.fault.report;

import org.ruoyi.fault.evidence.enums.EvidenceType;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交给报告叙事 Agent 的精简确定性事实。
 * 原始趋势点只保留在 {@link OperationReportResult} 快照中供网页绘图，不进入本对象。
 */
public record PreparedOperationReport(
    String reportId,
    OperationReportResult.Period period,
    Device device,
    Status status,
    List<MetricFact> metrics,
    OperationReportResult.AnalysisFacts analysisFacts,
    List<OperationReportResult.Event> events,
    List<OperationReportResult.StatusTimelineEvent> statusTimeline,
    DiagnosisSummary diagnosis,
    List<KnowledgeFact> knowledge,
    List<EvidenceFact> evidence,
    DataQualitySummary dataQuality,
    List<String> limitations
) {

    public PreparedOperationReport {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        analysisFacts = analysisFacts == null ? OperationReportResult.AnalysisFacts.empty() : analysisFacts;
        events = events == null ? List.of() : List.copyOf(events);
        statusTimeline = statusTimeline == null ? List.of() : List.copyOf(statusTimeline);
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public static PreparedOperationReport from(OperationReportResult report) {
        Map<String, OperationReportResult.MetricAnalysis> analyses = new LinkedHashMap<>();
        report.analysisFacts().metricAnalyses().forEach(item -> analyses.put(item.metric(), item));
        List<MetricFact> metrics = report.metrics().stream().map(metric -> {
            TelemetryMetricMetadata.MetricMetadata metadata = TelemetryMetricMetadata.of(metric.metricName(), report.metricUnits());
            OperationReportResult.MetricAnalysis analysis = analyses.get(metric.metricName());
            return new MetricFact(metadata.key(), metadata.displayName(), metadata.unit(), metric.current(), metric.average(),
                metric.minimum(), metric.maximum(), analysis == null ? null : analysis.startValue(),
                analysis == null ? null : analysis.endValue(), analysis == null ? null : analysis.delta(),
                analysis == null ? null : analysis.stdDev(), metric.count(), metric.peakAt());
        }).toList();
        List<KnowledgeFact> knowledge = report.evidence().stream()
            .filter(item -> item.type() == EvidenceType.KNOWLEDGE)
            .map(item -> new KnowledgeFact(item.evidenceCode(), item.source(), item.content())).toList();
        List<EvidenceFact> evidence = report.evidence().stream()
            .map(item -> new EvidenceFact(item.evidenceId(), item.evidenceCode(), item.type(), item.content())).toList();
        return new PreparedOperationReport(report.metadata().reportId(), report.period(),
            new Device(report.asset().deviceName(), report.asset().inverterName()),
            new Status(report.currentStatus(), report.periodStatus()), metrics, report.analysisFacts(), report.events(),
            report.statusTimeline(), report.diagnosis(), knowledge, evidence, report.dataQuality(), report.limitations());
    }

    public record Device(String deviceName, String inverterName) {
    }

    public record Status(ReportHealthStatus current, ReportHealthStatus period) {
    }

    /** 每个供 Agent 使用的指标均携带稳定 key、显示名和单位，以及已计算的统计事实。 */
    public record MetricFact(String key, String displayName, String unit, Double current, Double average,
                             Double minimum, Double maximum, Double start, Double end, Double delta,
                             Double stdDev, Integer sampleCount, LocalDateTime peakAt) {
    }

    public record KnowledgeFact(String evidenceCode, String source, String content) {
    }

    public record EvidenceFact(Long evidenceId, String evidenceCode, EvidenceType type, String summary) {
    }
}
