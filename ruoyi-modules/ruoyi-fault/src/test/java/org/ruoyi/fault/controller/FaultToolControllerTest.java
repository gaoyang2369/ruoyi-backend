package org.ruoyi.fault.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.fault.application.FaultCodeKnowledgeQueryService;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.controller.dto.FaultCodeRequest;
import org.ruoyi.fault.controller.dto.FaultDiagnosisContextRequest;
import org.ruoyi.fault.controller.dto.FaultDiagnosisRequest;
import org.ruoyi.fault.controller.dto.FaultStatusRequest;
import org.ruoyi.fault.controller.dto.TelemetryStatisticsRequest;
import org.ruoyi.fault.controller.dto.TelemetrySeriesRequest;
import org.ruoyi.fault.diagnosis.FaultDiagnosisOrchestrator;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.report.OperationReportOrchestrator;
import org.ruoyi.fault.telemetry.service.TelemetryQueryService;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FaultToolControllerTest {

    @Mock
    private TelemetryQueryService telemetryQueryService;
    @Mock
    private FaultCodeKnowledgeQueryService faultCodeKnowledgeQueryService;
    @Mock
    private FaultDiagnosisOrchestrator faultDiagnosisOrchestrator;
    @Mock
    private OperationReportOrchestrator operationReportOrchestrator;

    private FaultToolController controller;

    @BeforeEach
    void setUp() {
        FaultDiagnosisProperties properties = new FaultDiagnosisProperties();
        properties.setTimezone("Asia/Shanghai");
        properties.setDefaultWindowMinutes(30);
        controller = new FaultToolController(telemetryQueryService, faultCodeKnowledgeQueryService,
            faultDiagnosisOrchestrator, operationReportOrchestrator, properties);
    }

    @Test
    void statusDelegatesExplicitTimeRangeToTelemetryService() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 9, 10, 0);
        LocalDateTime end = start.plusMinutes(20);

        controller.status(new FaultStatusRequest("device", "inverter", start, end, null));

        verify(telemetryQueryService).queryTelemetry("device", "inverter", start, end);
    }

    @Test
    void statusCalculatesRecentTimeRange() {
        controller.status(new FaultStatusRequest("device", "inverter", null, null, 15));

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(telemetryQueryService).queryTelemetry(eq("device"), eq("inverter"), start.capture(), end.capture());
        assertEquals(start.getValue().plusMinutes(15), end.getValue());
    }

    @Test
    void statusResolvesInverterWhenCallerOmitsIt() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 9, 10, 0);
        LocalDateTime end = start.plusMinutes(30);
        when(telemetryQueryService.resolveInverterName("device")).thenReturn("resolved-inverter");

        controller.status(new FaultStatusRequest("device", null, start, end, null));

        verify(telemetryQueryService).resolveInverterName("device");
        verify(telemetryQueryService).queryTelemetry("device", "resolved-inverter", start, end);
    }

    @Test
    void faultCodeDelegatesToKnowledgeService() {
        controller.faultCode(new FaultCodeRequest("F30005", List.of(7L, 8L)));

        verify(faultCodeKnowledgeQueryService).query("F30005", List.of(7L, 8L));
    }

    @Test
    void telemetryStatisticsUsesWindowMinutesAndDelegatesToTelemetryService() {
        controller.telemetryStatistics(new TelemetryStatisticsRequest(
            "device", "inverter", 15, List.of("dcVoltage"), List.of("avg", "count")));

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(telemetryQueryService).validateStatisticsRequest(List.of("dcVoltage"), List.of("avg", "count"));
        verify(telemetryQueryService).queryStatistics(eq("device"), eq("inverter"), start.capture(), end.capture(),
            eq(List.of("dcVoltage")), eq(List.of("avg", "count")));
        assertEquals(start.getValue().plusMinutes(15), end.getValue());
    }

    @Test
    void telemetrySeriesDefaultsToOneMinuteBucketsAndDelegatesToTelemetryService() {
        controller.telemetrySeries(new TelemetrySeriesRequest(
            "device", "inverter", 15, List.of("motorTemp"), null));

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(telemetryQueryService).validateSeriesRequest(List.of("motorTemp"), 1);
        verify(telemetryQueryService).querySeries(eq("device"), eq("inverter"), start.capture(), end.capture(),
            eq(List.of("motorTemp")), eq(1));
        assertEquals(start.getValue().plusMinutes(15), end.getValue());
    }

    @Test
    void diagnoseMapsRequestToExistingCommand() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 9, 10, 0);
        LocalDateTime end = start.plusMinutes(30);
        FaultDiagnosisContextRequest context = new FaultDiagnosisContextRequest(
            1L, 2L, 3L, "tenant", "request-1");
        FaultDiagnosisRequest request = new FaultDiagnosisRequest(
            "device", "inverter", start, end, null, "stopped", List.of(7L), context);

        controller.diagnose(request);

        ArgumentCaptor<DiagnosisCommand> captor = ArgumentCaptor.forClass(DiagnosisCommand.class);
        verify(faultDiagnosisOrchestrator).diagnose(captor.capture());
        DiagnosisCommand command = captor.getValue();
        assertEquals("device", command.deviceName());
        assertEquals("inverter", command.inverterName());
        assertEquals(start, command.startTime());
        assertEquals(end, command.endTime());
        assertEquals("stopped", command.symptom());
        assertEquals(List.of(7L), command.knowledgeBaseIds());
        assertEquals(1L, command.context().agentId());
        assertEquals(2L, command.context().sessionId());
        assertEquals(3L, command.context().userId());
        assertEquals("tenant", command.context().tenantId());
        assertEquals("request-1", command.context().requestId());
    }

    @Test
    void diagnoseResolvesInverterWhenCallerOmitsIt() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 9, 10, 0);
        LocalDateTime end = start.plusMinutes(30);
        FaultDiagnosisContextRequest context = new FaultDiagnosisContextRequest(
            null, null, null, null, "request-2");
        when(telemetryQueryService.resolveInverterName("device")).thenReturn("resolved-inverter");

        controller.diagnose(new FaultDiagnosisRequest(
            "device", null, start, end, null, null, List.of(), context));

        ArgumentCaptor<DiagnosisCommand> captor = ArgumentCaptor.forClass(DiagnosisCommand.class);
        verify(telemetryQueryService).resolveInverterName("device");
        verify(faultDiagnosisOrchestrator).diagnose(captor.capture());
        assertEquals("resolved-inverter", captor.getValue().inverterName());
    }

    @Test
    void reportReusesDiagnosisRequestMappingAndDelegatesToReportOrchestrator() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 9, 10, 0);
        LocalDateTime end = start.plusMinutes(30);
        FaultDiagnosisContextRequest context = new FaultDiagnosisContextRequest(
            1L, 2L, 3L, "tenant", "report-request-1");

        controller.report(new FaultDiagnosisRequest(
            "device", "inverter", start, end, null, "stopped", List.of(7L), context));

        ArgumentCaptor<DiagnosisCommand> captor = ArgumentCaptor.forClass(DiagnosisCommand.class);
        verify(operationReportOrchestrator).generate(captor.capture());
        DiagnosisCommand command = captor.getValue();
        assertEquals("device", command.deviceName());
        assertEquals("inverter", command.inverterName());
        assertEquals(start, command.startTime());
        assertEquals(end, command.endTime());
        assertEquals("stopped", command.symptom());
        assertEquals(List.of(7L), command.knowledgeBaseIds());
        assertEquals("report-request-1", command.context().requestId());
    }

    @Test
    void rejectsPartialTimeRangeBeforeCallingService() {
        FaultStatusRequest request = new FaultStatusRequest(
            "device", "inverter", LocalDateTime.of(2026, 8, 9, 10, 0), null, null);

        assertThrows(ServiceException.class, () -> controller.status(request));
        org.mockito.Mockito.verify(telemetryQueryService, org.mockito.Mockito.never())
            .queryTelemetry(any(), any(), any(), any());
    }
}
