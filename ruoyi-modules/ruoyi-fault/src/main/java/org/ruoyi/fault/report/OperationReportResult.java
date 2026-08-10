package org.ruoyi.fault.report;

import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.evidence.enums.EvidenceType;
import org.ruoyi.fault.telemetry.model.CodeOccurrence;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.OperationStatistics;
import org.ruoyi.fault.telemetry.model.StatusEvent;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetryStatistics;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
    ReportHealthStatus overallStatus,
    Summary summary,
    DataQualitySummary dataQuality,
    List<Metric> metrics,
    List<Event> events,
    List<StatusTimelineEvent> statusTimeline,
    DiagnosisResult diagnosis,
    List<Recommendation> recommendations,
    List<Evidence> evidence,
    List<String> limitations
) {

    public static final String REPORT_TYPE = "OPERATION_REPORT_V2";

    public OperationReportResult {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        events = events == null ? List.of() : List.copyOf(events);
        statusTimeline = statusTimeline == null ? List.of() : List.copyOf(statusTimeline);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    /**
     * 从报告编排已得到的遥测和诊断快照投影出 V2。此方法只做对象转换，不访问数据库、不重新诊断。
     */
    public static OperationReportResult fromSources(String reportId, String deviceName, String inverterName,
                                                     LocalDateTime requestedStart, LocalDateTime requestedEnd,
                                                     LocalDateTime generatedAt, ReportHealthStatus overallStatus,
                                                     Summary summary, TelemetryQueryResult telemetry,
                                                     DiagnosisResult diagnosis) {
        TelemetryQueryResult source = telemetry == null ? emptyTelemetry() : telemetry;
        return new OperationReportResult(
            new Metadata(reportId, generatedAt, REPORT_TYPE),
            new Asset(deviceName, inverterName),
            new Period(requestedStart, requestedEnd, source.startTime(), source.endTime(),
                source.fallbackToLatestData(), source.latestObservedAt(), source.sourceDigest()),
            overallStatus,
            summary,
            source.quality(),
            metricsOf(source.statistics(), source.operation()),
            eventsOf(source.operation(), source.faultCodes(), source.alarmCodes(), source.statusEvents()),
            timelineOf(source.statusEvents()),
            diagnosis,
            recommendationsOf(diagnosis),
            evidenceOf(diagnosis),
            diagnosis == null ? List.of() : diagnosis.limitations());
    }

    /**
     * 兼容旧报告构造方式。新代码应调用 {@link #fromSources} 并使用 V2 字段。
     */
    @Deprecated
    public OperationReportResult(String reportCode, String deviceName, String inverterName,
                                 LocalDateTime requestedStartTime, LocalDateTime requestedEndTime,
                                 LocalDateTime generatedAt, ReportHealthStatus healthStatus, String summary,
                                 TelemetryQueryResult telemetry, DiagnosisResult diagnosis) {
        this(fromSources(reportCode, deviceName, inverterName, requestedStartTime, requestedEndTime,
            generatedAt, healthStatus,
            new Summary(summary, diagnosis == null ? List.of() : diagnosis.faultCodes(),
                diagnosis == null ? List.of() : diagnosis.alarmCodes(),
                telemetry != null && !telemetry.fallbackToLatestData()
                    && (diagnosis == null || diagnosis.status() != DiagnosisStatus.DATA_INSUFFICIENT)),
            telemetry, diagnosis));
    }

    private OperationReportResult(OperationReportResult value) {
        this(value.metadata, value.asset, value.period, value.overallStatus, value.summary, value.dataQuality,
            value.metrics, value.events, value.statusTimeline, value.diagnosis, value.recommendations,
            value.evidence, value.limitations);
    }

    private static TelemetryQueryResult emptyTelemetry() {
        return new TelemetryQueryResult(null, null, null, null, List.of(), List.of(), List.of(), List.of(),
            null, null, false, null, List.of(), null);
    }

    private static List<Metric> metricsOf(TelemetryStatistics statistics, OperationStatistics operation) {
        if (statistics == null) {
            return List.of();
        }
        int count = statistics.sampleCount();
        return List.of(
            new Metric("actual-power", null, statistics.avgActualPower(), statistics.minActualPower(),
                statistics.maxActualPower(), count, null),
            new Metric("motor-temp", null, statistics.avgMotorTemp(), statistics.minMotorTemp(),
                statistics.maxMotorTemp(), count, operation == null ? null : operation.maxMotorTempAt()),
            new Metric("inverter-temp", null, statistics.avgInverterTemp(), statistics.minInverterTemp(),
                statistics.maxInverterTemp(), count, null),
            new Metric("motor-load-rate", null, null, null, statistics.maxMotorLoadRate(), count,
                operation == null ? null : operation.maxMotorLoadRateAt()),
            new Metric("inverter-load-rate", null, null, null, statistics.maxInverterLoadRate(), count, null));
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

    /** 一个故障码或报警码在窗口内的聚合事件。 */
    public record Event(String code, FaultCodeType type, boolean active, LocalDateTime firstSeenAt,
                        LocalDateTime lastSeenAt, LocalDateTime recoveredAt, int occurrenceCount) {
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
