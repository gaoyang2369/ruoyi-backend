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
import org.ruoyi.fault.telemetry.model.StatusEvent;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetryStatistics;
import org.ruoyi.fault.telemetry.service.TelemetryQueryService;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
            new FaultDiagnosisProperties());
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
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any())).thenReturn(telemetry);
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
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any())).thenReturn(telemetry);
        when(faultDiagnosisOrchestrator.diagnose(any(DiagnosisCommand.class), any(TelemetryQueryResult.class)))
            .thenReturn(diagnosis(DiagnosisStatus.FAULT_DETECTED, List.of("F30005")));

        OperationReportResult result = orchestrator.generate(command());

        assertTrue(result.metadata().reportId().startsWith("RP-"));
        assertEquals(OperationReportResult.REPORT_TYPE, result.metadata().reportType());
        assertEquals(ReportHealthStatus.FAULT, result.overallStatus());
        assertEquals(command().startTime(), result.period().windowStart());
        assertEquals(command().endTime(), result.period().windowEnd());
        assertTrue(result.summary().conclusion().contains("F30005"));
        assertFalse(result.summary().conclusion().contains("次"));
        assertEquals(List.of("F30005"), result.summary().faultCodes());
        assertEquals(1, result.events().size());
        assertEquals("F30005", result.events().get(0).code());
        assertEquals(3, result.events().get(0).occurrenceCount());
        assertTrue(result.metadata().generatedAt() != null);
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
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any())).thenReturn(telemetry);
        when(faultDiagnosisOrchestrator.diagnose(any(DiagnosisCommand.class), any(TelemetryQueryResult.class)))
            .thenReturn(diagnosis(DiagnosisStatus.FAULT_DETECTED, List.of("F30005"), List.of("A07089")));

        OperationReportResult result = orchestrator.generate(command());

        assertTrue(result.events().stream().anyMatch(event -> event.code().equals("F30005") && event.active()));
        assertTrue(result.events().stream().anyMatch(event -> event.code().equals("A07089")
            && !event.active() && recovered.equals(event.recoveredAt())));
    }

    private ReportHealthStatus statusOf(DiagnosisStatus status) {
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any()))
            .thenReturn(telemetry(List.of(), List.of()));
        when(faultDiagnosisOrchestrator.diagnose(any(DiagnosisCommand.class), any(TelemetryQueryResult.class)))
            .thenReturn(diagnosis(status));
        return orchestrator.generate(command()).overallStatus();
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

}
