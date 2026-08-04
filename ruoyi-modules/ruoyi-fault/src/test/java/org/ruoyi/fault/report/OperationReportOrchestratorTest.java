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
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetryStatistics;
import org.ruoyi.fault.telemetry.service.TelemetryQueryService;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void reusesSingleTelemetrySnapshotForDiagnosisAndReport() {
        TelemetryQueryResult telemetry = telemetry(List.of(), List.of());
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any())).thenReturn(telemetry);
        when(faultDiagnosisOrchestrator.diagnose(any(DiagnosisCommand.class), any(TelemetryQueryResult.class)))
            .thenReturn(diagnosis(DiagnosisStatus.NO_EXPLICIT_FAULT));

        OperationReportResult result = orchestrator.generate(command());

        ArgumentCaptor<TelemetryQueryResult> captor = ArgumentCaptor.forClass(TelemetryQueryResult.class);
        verify(faultDiagnosisOrchestrator).diagnose(any(DiagnosisCommand.class), captor.capture());
        assertSame(telemetry, captor.getValue());
        assertSame(telemetry, result.telemetry());
    }

    @Test
    void buildsReportCodeGeneratedAtAndFaultSummary() {
        LocalDateTime first = LocalDateTime.of(2026, 1, 1, 0, 5);
        TelemetryQueryResult telemetry = telemetry(
            List.of(new CodeOccurrence("F30005", 3, first, first.plusMinutes(2))), List.of());
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any())).thenReturn(telemetry);
        when(faultDiagnosisOrchestrator.diagnose(any(DiagnosisCommand.class), any(TelemetryQueryResult.class)))
            .thenReturn(diagnosis(DiagnosisStatus.FAULT_DETECTED, List.of("F30005")));

        OperationReportResult result = orchestrator.generate(command());

        assertTrue(result.reportCode().startsWith("RP-"));
        assertEquals(ReportHealthStatus.FAULT, result.healthStatus());
        assertEquals(command().startTime(), result.requestedStartTime());
        assertEquals(command().endTime(), result.requestedEndTime());
        // 摘要只点代码清单，出现明细由第 6 节呈现
        assertTrue(result.summary().contains("F30005"));
        assertFalse(result.summary().contains("次"));
        assertTrue(result.generatedAt() != null);
    }

    private ReportHealthStatus statusOf(DiagnosisStatus status) {
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any()))
            .thenReturn(telemetry(List.of(), List.of()));
        when(faultDiagnosisOrchestrator.diagnose(any(DiagnosisCommand.class), any(TelemetryQueryResult.class)))
            .thenReturn(diagnosis(status));
        return orchestrator.generate(command()).healthStatus();
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
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new DiagnosisResult("request", status, false, "device", "inverter",
            start, start.plusMinutes(30), start, start.plusMinutes(30), false, start.plusMinutes(29),
            null, new DataQualitySummary(10, 10, 0, 0, 0, 1D, true), statistics(),
            faultCodes, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static TelemetryStatistics statistics() {
        return new TelemetryStatistics(10, null, null, null, null, null, null, null, null, null, null, null);
    }

}
