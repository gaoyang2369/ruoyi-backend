package org.ruoyi.fault.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.diagnosis.FaultDiagnosisOrchestrator;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.context.DiagnosisRequestContext;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.telemetry.model.CodeOccurrence;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.OperationStatistics;
import org.ruoyi.fault.telemetry.model.ReportTelemetrySample;
import org.ruoyi.fault.telemetry.model.StatusEvent;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetryReportSnapshot;
import org.ruoyi.fault.telemetry.model.TelemetrySeriesPoint;
import org.ruoyi.fault.telemetry.model.TelemetrySeriesResult;
import org.ruoyi.fault.telemetry.model.TelemetryStatistics;
import org.ruoyi.fault.telemetry.model.TelemetryStatisticsResult;
import org.ruoyi.fault.telemetry.service.TelemetryQueryService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("dev")
class OperationReportOrchestratorTest {

    @Mock
    private TelemetryQueryService telemetryQueryService;
    @Mock
    private FaultDiagnosisOrchestrator faultDiagnosisOrchestrator;

    private OperationReportOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new OperationReportOrchestrator(telemetryQueryService, faultDiagnosisOrchestrator,
            new OperationReportAnalysisService(), new FaultDiagnosisProperties());
    }

    @Test
    void mapsDiagnosisStatusesToHealthStatuses() {
        assertEquals(ReportHealthStatus.FAULT, statusOf(DiagnosisStatus.FAULT_DETECTED));
        assertEquals(ReportHealthStatus.ATTENTION, statusOf(DiagnosisStatus.WARNING_DETECTED));
        assertEquals(ReportHealthStatus.NORMAL, statusOf(DiagnosisStatus.NO_EXPLICIT_FAULT));
        assertEquals(ReportHealthStatus.UNKNOWN, statusOf(DiagnosisStatus.DATA_INSUFFICIENT));
    }

    @Test
    void reusesSingleTelemetrySnapshotForDiagnosisAndProjectsItToV2() {
        TelemetryQueryResult telemetry = telemetry(List.of(), List.of());
        when(telemetryQueryService.queryReportTelemetry(any(), any(), any(), any())).thenReturn(reportSnapshot(telemetry));
        when(faultDiagnosisOrchestrator.diagnose(any(DiagnosisCommand.class), any(TelemetryQueryResult.class)))
            .thenReturn(diagnosis(DiagnosisStatus.NO_EXPLICIT_FAULT));

        OperationReportResult result = orchestrator.generate(command());

        ArgumentCaptor<TelemetryQueryResult> captor = ArgumentCaptor.forClass(TelemetryQueryResult.class);
        verify(faultDiagnosisOrchestrator).diagnose(any(DiagnosisCommand.class), captor.capture());
        assertEquals(telemetry, captor.getValue());
        assertEquals(telemetry.quality(), result.dataQuality());
        assertEquals(telemetry.startTime(), result.period().analysisWindowStart());
        assertEquals(telemetry.statusEvents().size(), result.statusTimeline().size());
    }

    @Test
    void buildsStructuredV2MetadataSummaryMetricsAndFaultEvents() {
        LocalDateTime first = LocalDateTime.of(2026, 1, 1, 0, 5);
        TelemetryQueryResult telemetry = telemetry(
            List.of(new CodeOccurrence("F30005", 3, first, first.plusMinutes(2))), List.of());
        when(telemetryQueryService.queryReportTelemetry(any(), any(), any(), any())).thenReturn(reportSnapshot(telemetry));
        when(faultDiagnosisOrchestrator.diagnose(any(DiagnosisCommand.class), any(TelemetryQueryResult.class)))
            .thenReturn(diagnosis(DiagnosisStatus.FAULT_DETECTED, List.of("F30005")));

        OperationReportResult result = orchestrator.generate(command());

        assertTrue(result.metadata().reportId().startsWith("RP-"));
        assertEquals(OperationReportResult.REPORT_TYPE, result.metadata().reportType());
        assertEquals(ReportHealthStatus.FAULT, result.periodStatus());
        assertEquals(ReportHealthStatus.NORMAL, result.currentStatus());
        assertEquals(command().startTime(), result.period().windowStart());
        assertEquals(command().endTime(), result.period().windowEnd());
        assertTrue(result.summary().conclusion().contains("F30005"));
        assertFalse(result.summary().conclusion().contains("次"));
        assertEquals(List.of("F30005"), result.summary().faultCodes());
        assertEquals(1, result.events().size());
        assertEquals("F30005", result.events().get(0).code());
        assertEquals(3, result.events().get(0).sampleHitCount());
        assertEquals(8, result.metrics().size());
        assertEquals(620D, metric(result, "dcVoltage").average());
        assertEquals(625D, metric(result, "dcVoltage").current());
        assertEquals(null, metric(result, "currentActual").current());
        assertEquals(12D, metric(result, "currentActual").average());
        assertEquals(1450D, metric(result, "speedActual").average());
        assertEquals(2, result.trends().get(0).points().size());
        assertTrue(result.metadata().generatedAt() != null);
    }

    @Test
    void alignsCurrentValuesWithAnalysisEndValuesFromTheLastValidSamples() {
        TelemetryQueryResult telemetry = telemetry(List.of(), List.of());
        TelemetryReportSnapshot base = reportSnapshot(telemetry);
        LocalDateTime start = telemetry.startTime();
        TelemetryReportSnapshot snapshot = new TelemetryReportSnapshot(telemetry, base.statistics(), base.series(), List.of(
            new ReportTelemetrySample(start, metricsAt(610D, 10D, 1400D, 2D, 40D, 30D, 60D, 55D)),
            new ReportTelemetrySample(start.plusMinutes(29), metricsAt(611D, 11D, 780.442D, 3D, 41D, 31D, 61D, 56D))));
        when(telemetryQueryService.queryReportTelemetry(any(), any(), any(), any())).thenReturn(snapshot);
        when(faultDiagnosisOrchestrator.diagnose(any(DiagnosisCommand.class), any(TelemetryQueryResult.class)))
            .thenReturn(diagnosis(DiagnosisStatus.NO_EXPLICIT_FAULT));

        OperationReportResult result = orchestrator.generate(command());

        for (OperationReportResult.MetricAnalysis analysis : result.analysisFacts().metricAnalyses()) {
            assertEquals(analysis.endValue(), metric(result, analysis.metric()).current());
        }
        assertEquals(780.442D, metric(result, "speedActual").current());
    }

    @Test
    void preservesActiveAndRecoveredCodeEventsInV2() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime first = start.plusMinutes(5);
        LocalDateTime recovered = start.plusMinutes(8);
        TelemetryQueryResult telemetry = new TelemetryQueryResult("device", start, start.plusMinutes(30),
            new DataQualitySummary(10, 10, 0, 0, 0, 1D, true), List.of("F30005"), List.of("A07089"),
            List.of(), List.of(new StatusEvent(first, "42", "F30005", "A07089"),
                new StatusEvent(recovered, "42", "F30005", null)), statistics(), "digest", false,
            start.plusMinutes(29), List.of(), new OperationStatistics(null, null,
                List.of(new CodeOccurrence("F30005", 3, first, first.plusMinutes(2), true, null)),
                List.of(new CodeOccurrence("A07089", 2, first, first.plusMinutes(1), false, recovered))));
        when(telemetryQueryService.queryReportTelemetry(any(), any(), any(), any())).thenReturn(reportSnapshot(telemetry));
        when(faultDiagnosisOrchestrator.diagnose(any(DiagnosisCommand.class), any(TelemetryQueryResult.class)))
            .thenReturn(diagnosis(DiagnosisStatus.FAULT_DETECTED, List.of("F30005"), List.of("A07089")));

        OperationReportResult result = orchestrator.generate(command());

        assertTrue(result.events().stream().anyMatch(event -> event.code().equals("F30005") && event.active()));
        assertTrue(result.events().stream().anyMatch(event -> event.code().equals("A07089")
            && !event.active() && recovered.equals(event.recoveredAt())));
        assertEquals(ReportHealthStatus.FAULT, result.currentStatus());
    }

    @Test
    void splitsRepeatedCodeIntoSeparateReportEventEpisodes() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 21, 11);
        LocalDateTime firstStart = start.plusMinutes(19);
        LocalDateTime firstRecovered = firstStart.plusMinutes(2);
        LocalDateTime secondStart = start.plusMinutes(49);
        LocalDateTime secondRecovered = secondStart.plusMinutes(2);
        TelemetryQueryResult telemetry = new TelemetryQueryResult("device", start, start.plusHours(1),
            new DataQualitySummary(10, 10, 0, 0, 0, 1D, true), List.of(), List.of("A07089"), List.of(),
            List.of(new StatusEvent(start, "0", null, null),
                new StatusEvent(firstStart, "42", null, "A07089", firstStart.minusSeconds(4)),
                new StatusEvent(firstRecovered, "0", null, null, firstRecovered.minusSeconds(4)),
                new StatusEvent(secondStart, "42", null, "A07089", secondStart.minusSeconds(4)),
                new StatusEvent(secondRecovered, "0", null, null, secondRecovered.minusSeconds(4))),
            statistics(), "digest", false, start.plusMinutes(59), List.of(), new OperationStatistics(null, null,
                List.of(), List.of(new CodeOccurrence("A07089", 4, firstStart, secondRecovered.minusSeconds(4),
                    false, secondRecovered))));
        when(telemetryQueryService.queryReportTelemetry(any(), any(), any(), any())).thenReturn(reportSnapshot(telemetry));
        when(faultDiagnosisOrchestrator.diagnose(any(DiagnosisCommand.class), any(TelemetryQueryResult.class)))
            .thenReturn(diagnosis(DiagnosisStatus.WARNING_DETECTED, List.of(), List.of("A07089")));

        OperationReportResult result = orchestrator.generate(command());

        assertEquals(2, result.events().size());
        assertEquals(firstStart, result.events().get(0).firstSeenAt());
        assertEquals(firstRecovered, result.events().get(0).recoveredAt());
        assertEquals(firstRecovered.minusSeconds(4), result.events().get(0).lastSeenAt());
        assertEquals(secondStart, result.events().get(1).firstSeenAt());
        assertEquals(secondRecovered, result.events().get(1).recoveredAt());
        assertEquals(secondRecovered.minusSeconds(4), result.events().get(1).lastSeenAt());
        assertEquals(2, result.analysisFacts().eventComparisons().size());
        assertEquals(List.of(firstStart, secondStart), result.analysisFacts().eventComparisons().stream()
            .map(OperationReportResult.EventComparison::startTime).toList());
    }

    @Test
    void dataInsufficientReportDoesNotExposeMetricsOrTrends() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        TelemetryQueryResult telemetry = new TelemetryQueryResult("device", start, start.plusMinutes(30),
            new DataQualitySummary(3, 3, 0, 0, 27, 0.1D, false), List.of(), List.of(), List.of(), List.of(),
            statistics(), "digest", false, start.plusMinutes(2), List.of(), OperationStatistics.empty());
        when(telemetryQueryService.queryReportTelemetry(any(), any(), any(), any()))
            .thenReturn(new TelemetryReportSnapshot(telemetry, null, null));
        when(faultDiagnosisOrchestrator.diagnose(any(DiagnosisCommand.class), any(TelemetryQueryResult.class)))
            .thenReturn(diagnosis(DiagnosisStatus.DATA_INSUFFICIENT));

        OperationReportResult result = orchestrator.generate(command());

        assertEquals(ReportHealthStatus.UNKNOWN, result.periodStatus());
        assertEquals(ReportHealthStatus.UNKNOWN, result.currentStatus());
        assertTrue(result.metrics().isEmpty());
        assertTrue(result.trends().isEmpty());
    }

    @Test
    void repeatedGenerationCreatesIndependentReportCodes() {
        TelemetryQueryResult telemetry = telemetry(List.of(), List.of());
        when(telemetryQueryService.queryReportTelemetry(any(), any(), any(), any()))
            .thenReturn(reportSnapshot(telemetry));
        when(faultDiagnosisOrchestrator.diagnose(any(DiagnosisCommand.class), any(TelemetryQueryResult.class)))
            .thenReturn(diagnosis(DiagnosisStatus.NO_EXPLICIT_FAULT));

        OperationReportResult first = orchestrator.generate(command());
        OperationReportResult second = orchestrator.generate(command());

        assertNotEquals(first.metadata().reportId(), second.metadata().reportId());
        verify(telemetryQueryService, times(2)).queryReportTelemetry(any(), any(), any(), any());
    }

    private ReportHealthStatus statusOf(DiagnosisStatus status) {
        when(telemetryQueryService.queryReportTelemetry(any(), any(), any(), any()))
            .thenReturn(reportSnapshot(telemetry(List.of(), List.of())));
        when(faultDiagnosisOrchestrator.diagnose(any(DiagnosisCommand.class), any(TelemetryQueryResult.class)))
            .thenReturn(diagnosis(status));
        return orchestrator.generate(command()).periodStatus();
    }

    private static DiagnosisCommand command() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new DiagnosisCommand("device", "inverter", start, start.plusMinutes(30), null, List.of(),
            new DiagnosisRequestContext(1L, null, 3L, "tenant", "request"));
    }

    private static TelemetryQueryResult telemetry(List<CodeOccurrence> faults, List<CodeOccurrence> alarms) {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new TelemetryQueryResult("device", start, start.plusMinutes(30),
            new DataQualitySummary(10, 10, 0, 0, 0, 1D, true), List.of(), List.of(), List.of(), List.of(),
            statistics(), "digest", false, start.plusMinutes(29), List.of(),
            new OperationStatistics(null, null, faults, alarms));
    }

    private static DiagnosisResult diagnosis(DiagnosisStatus status) {
        return diagnosis(status, List.of());
    }

    private static DiagnosisResult diagnosis(DiagnosisStatus status, List<String> faultCodes) {
        return diagnosis(status, faultCodes, List.of());
    }

    private static DiagnosisResult diagnosis(DiagnosisStatus status, List<String> faultCodes, List<String> alarmCodes) {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new DiagnosisResult("request", status, false, "device", "inverter",
            start, start.plusMinutes(30), start, start.plusMinutes(30), false, start.plusMinutes(29),
            null, new DataQualitySummary(10, 10, 0, 0, 0, 1D, true), statistics(),
            faultCodes, alarmCodes, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static TelemetryStatistics statistics() {
        return new TelemetryStatistics(10, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static TelemetryReportSnapshot reportSnapshot(TelemetryQueryResult telemetry) {
        if (telemetry.quality() == null || !telemetry.quality().sufficient()) {
            return new TelemetryReportSnapshot(telemetry, null, null);
        }
        LocalDateTime start = telemetry.startTime();
        TelemetryStatisticsResult statistics = new TelemetryStatisticsResult("device", "inverter", start, telemetry.endTime(),
            10, Map.of(
                "dcVoltage", Map.of("avg", 620D, "min", 610D, "max", 630D, "count", 10L),
                "currentActual", Map.of("avg", 12D, "min", 10D, "max", 14D, "count", 10L),
                "speedActual", Map.of("avg", 1450D, "min", 1400D, "max", 1500D, "count", 10L),
                "actualPower", Map.of("avg", 3D, "min", 2D, "max", 4D, "count", 10L),
                "motorTemp", Map.of("avg", 45D, "min", 40D, "max", 50D, "count", 10L),
                "inverterTemp", Map.of("avg", 35D, "min", 30D, "max", 40D, "count", 10L),
                "motorLoadRate", Map.of("avg", 70D, "min", 60D, "max", 80D, "count", 10L),
                "inverterLoadRate", Map.of("avg", 65D, "min", 55D, "max", 75D, "count", 10L)), telemetry.quality());
        TelemetrySeriesResult series = new TelemetrySeriesResult("device", "inverter", start, telemetry.endTime(), 1, 10,
            Map.of("dcVoltage", List.of(new TelemetrySeriesPoint(start, 620D, 2L),
                new TelemetrySeriesPoint(start.plusMinutes(1), 625D, 3L))), telemetry.quality());
        return new TelemetryReportSnapshot(telemetry, statistics, series);
    }

    private static OperationReportResult.Metric metric(OperationReportResult result, String name) {
        return result.metrics().stream().filter(metric -> metric.metricName().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, Double> metricsAt(double voltage, double current, double speed, double power,
                                                  double motorTemp, double inverterTemp, double motorLoad,
                                                  double inverterLoad) {
        return Map.of("dcVoltage", voltage, "currentActual", current, "speedActual", speed, "actualPower", power,
            "motorTemp", motorTemp, "inverterTemp", inverterTemp, "motorLoadRate", motorLoad,
            "inverterLoadRate", inverterLoad);
    }

}
