package org.ruoyi.fault.diagnosis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.fault.application.BasicFaultRuleEngine;
import org.ruoyi.fault.application.DiagnosisCommandValidator;
import org.ruoyi.fault.application.DiagnosisResultAssembler;
import org.ruoyi.fault.application.FaultDiagnosisEvidenceRecorder;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.context.DiagnosisRequestContext;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.knowledge.FaultKnowledgePort;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.fault.evidence.entity.DiagnosisCaseEntity;
import org.ruoyi.fault.evidence.entity.DiagnosisStepEntity;
import org.ruoyi.fault.evidence.enums.EvidenceType;
import org.ruoyi.fault.evidence.model.EvidenceAppendCommand;
import org.ruoyi.fault.evidence.model.EvidenceAppendResult;
import org.ruoyi.fault.evidence.service.DiagnosisCaseService;
import org.ruoyi.fault.evidence.service.DiagnosisStepService;
import org.ruoyi.fault.evidence.service.EvidenceChainService;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.service.TelemetryQueryService;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FaultDiagnosisOrchestratorTest {

    @Mock
    private TelemetryQueryService telemetryQueryService;
    @Mock
    private FaultKnowledgePort faultKnowledgePort;
    @Mock
    private DiagnosisCaseService diagnosisCaseService;
    @Mock
    private DiagnosisStepService diagnosisStepService;
    @Mock
    private EvidenceChainService evidenceChainService;

    private FaultDiagnosisOrchestrator orchestrator;
    private FaultDiagnosisEvidenceRecorder evidenceRecorder;

    @BeforeEach
    void setUp() {
        DiagnosisCaseEntity diagnosisCase = new DiagnosisCaseEntity();
        diagnosisCase.setId(1L);
        DiagnosisStepEntity step = new DiagnosisStepEntity();
        step.setId(1L);
        // 共享桩并非每个测试都会消费（失败路径提前中断），使用 lenient 避免严格模式误报
        lenient().when(diagnosisCaseService.create(any())).thenReturn(diagnosisCase);
        lenient().when(diagnosisStepService.start(any())).thenReturn(step);
        lenient().when(evidenceChainService.append(any())).thenReturn(new EvidenceAppendResult(1L, "EV-001", 1,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        evidenceRecorder = new FaultDiagnosisEvidenceRecorder(diagnosisCaseService, diagnosisStepService,
            evidenceChainService);
        orchestrator = new FaultDiagnosisOrchestrator(new DiagnosisCommandValidator(), telemetryQueryService,
            faultKnowledgePort, new BasicFaultRuleEngine(), new DiagnosisResultAssembler(), evidenceRecorder);
    }

    @Test
    void queriesTelemetryAndDeduplicatesFaultCodesInFirstSeenOrder() {
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any()))
            .thenReturn(telemetry(true, List.of(" f002 ", "F001", "f002"), List.of()));
        when(faultKnowledgePort.query(any())).thenAnswer(invocation ->
            FaultKnowledgeResult.notFound(invocation.getArgument(0, FaultKnowledgeQuery.class)));

        DiagnosisResult result = orchestrator.diagnose(command(List.of(7L)));

        ArgumentCaptor<FaultKnowledgeQuery> captor = ArgumentCaptor.forClass(FaultKnowledgeQuery.class);
        verify(faultKnowledgePort, times(2)).query(captor.capture());
        assertEquals(List.of("F002", "F001"), captor.getAllValues().stream().map(FaultKnowledgeQuery::faultCode).toList());
        verify(telemetryQueryService).queryTelemetry("device", "inverter", command(List.of(7L)).startTime(),
            command(List.of(7L)).endTime());
        assertEquals(DiagnosisStatus.FAULT_DETECTED, result.status());
    }

    @Test
    void telemetryEvidenceDigestIsStoredWithoutAlgorithmPrefix() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        TelemetryQueryResult prefixed = new TelemetryQueryResult("device", start, start.plusMinutes(5),
            new DataQualitySummary(1, 1, 0, 0, 0, 1D, true), List.of(), List.of(), List.of(), null,
            "sha256:" + "a".repeat(64), false);
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any())).thenReturn(prefixed);

        orchestrator.diagnose(command(List.of()));

        ArgumentCaptor<EvidenceAppendCommand> captor = ArgumentCaptor.forClass(EvidenceAppendCommand.class);
        verify(evidenceChainService, atLeastOnce()).append(captor.capture());
        EvidenceAppendCommand telemetryEvidence = captor.getAllValues().stream()
            .filter(item -> item.evidenceType() == EvidenceType.TELEMETRY)
            .findFirst().orElseThrow();
        assertEquals("a".repeat(64), telemetryEvidence.sourceDigest());
    }

    @Test
    void doesNotQueryKnowledgeWithoutFaultCodeOrKnowledgeBaseBinding() {
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any()))
            .thenReturn(telemetry(true, List.of(), List.of("A001")));

        orchestrator.diagnose(command(List.of(7L)));
        verify(faultKnowledgePort, never()).query(any());

        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any()))
            .thenReturn(telemetry(true, List.of("F001"), List.of()));
        DiagnosisResult result = orchestrator.diagnose(command(List.of()));
        verify(faultKnowledgePort, never()).query(any());
        assertEquals(KnowledgeLookupStatus.SKIPPED, result.candidateFaults().get(0).knowledgeStatus());
    }

    @Test
    void preservesMatchedAndNotFoundWithoutMarkingNotFoundPartial() {
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any()))
            .thenReturn(telemetry(true, List.of("F001"), List.of()));
        when(faultKnowledgePort.query(any())).thenAnswer(invocation ->
            FaultKnowledgeResult.notFound(invocation.getArgument(0, FaultKnowledgeQuery.class)));

        DiagnosisResult notFound = orchestrator.diagnose(command(List.of(7L)));
        assertEquals(KnowledgeLookupStatus.NOT_FOUND, notFound.candidateFaults().get(0).knowledgeStatus());
        assertFalse(notFound.partial());
    }

    @Test
    void matchedKnowledgeProducesMatchedCandidate() {
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any()))
            .thenReturn(telemetry(true, List.of("F001"), List.of()));
        when(faultKnowledgePort.query(any())).thenAnswer(invocation -> {
            FaultKnowledgeQuery query = invocation.getArgument(0, FaultKnowledgeQuery.class);
            return FaultKnowledgeResult.matched(query, List.of(new FaultKnowledgeEvidence(7L, "doc", "manual",
                "fragment", 0, "F001 manual entry")));
        });

        DiagnosisResult result = orchestrator.diagnose(command(List.of(7L)));

        assertEquals(KnowledgeLookupStatus.MATCHED, result.candidateFaults().get(0).knowledgeStatus());
    }

    @Test
    void failedOrThrownKnowledgeLookupIsPartialAndContinues() {
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any()))
            .thenReturn(telemetry(true, List.of("F001", "F002"), List.of()));
        when(faultKnowledgePort.query(any())).thenThrow(new IllegalStateException("unavailable"))
            .thenAnswer(invocation -> FaultKnowledgeResult.notFound(invocation.getArgument(0, FaultKnowledgeQuery.class)));

        DiagnosisResult result = orchestrator.diagnose(command(List.of(7L)));

        assertTrue(result.partial());
        assertEquals(List.of(KnowledgeLookupStatus.FAILED, KnowledgeLookupStatus.NOT_FOUND),
            result.candidateFaults().stream().map(candidate -> candidate.knowledgeStatus()).toList());
        verify(faultKnowledgePort, times(2)).query(any());
    }

    @Test
    void telemetryFailurePropagatesAndValidationFailureCallsNoExternalService() {
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any()))
            .thenThrow(new ServiceException("telemetry unavailable"));
        assertThrows(ServiceException.class, () -> orchestrator.diagnose(command(List.of(7L))));

        FaultDiagnosisOrchestrator invalidOrchestrator = new FaultDiagnosisOrchestrator(new DiagnosisCommandValidator(),
            telemetryQueryService, faultKnowledgePort, new BasicFaultRuleEngine(), new DiagnosisResultAssembler(), evidenceRecorder);
        assertThrows(ServiceException.class, () -> invalidOrchestrator.diagnose(new DiagnosisCommand(" ", "inverter",
            command(List.of()).startTime(), command(List.of()).endTime(), null, List.of(), command(List.of()).context())));
        verify(faultKnowledgePort, never()).query(any());
    }

    @Test
    void dataInsufficientKeepsExplicitFaultButWinsStatusPriority() {
        when(telemetryQueryService.queryTelemetry(any(), any(), any(), any()))
            .thenReturn(telemetry(false, List.of("F001"), List.of()));
        when(faultKnowledgePort.query(any())).thenAnswer(invocation ->
            FaultKnowledgeResult.failed(invocation.getArgument(0, FaultKnowledgeQuery.class)));

        DiagnosisResult result = orchestrator.diagnose(command(List.of(7L)));

        assertEquals(DiagnosisStatus.DATA_INSUFFICIENT, result.status());
        assertEquals("F001", result.candidateFaults().get(0).faultCode());
    }

    private static DiagnosisCommand command(List<Long> knowledgeBaseIds) {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new DiagnosisCommand("device", "inverter", start, start.plusMinutes(5), "symptom", knowledgeBaseIds,
            new DiagnosisRequestContext(1L, 2L, 3L, "tenant", "request"));
    }

    private static TelemetryQueryResult telemetry(boolean sufficient, List<String> faultCodes, List<String> alarmCodes) {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new TelemetryQueryResult("device", start, start.plusMinutes(5),
            new DataQualitySummary(1, 1, 0, 0, 0, 1D, sufficient), faultCodes, alarmCodes, List.of(), null,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", false);
    }
}
