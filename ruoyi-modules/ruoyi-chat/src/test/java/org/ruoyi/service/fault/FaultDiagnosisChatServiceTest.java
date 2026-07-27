package org.ruoyi.service.fault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.dto.request.FaultDiagnosisChatInput;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.domain.enums.agent.AgentExecutionMode;
import org.ruoyi.domain.enums.agent.AgentScenarioCode;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.diagnosis.FaultDiagnosisOrchestrator;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisObservation;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaultDiagnosisChatServiceTest {

    @Mock
    private FaultDiagnosisOrchestrator faultDiagnosisOrchestrator;
    @Mock
    private FaultDiagnosisProperties faultDiagnosisProperties;
    @InjectMocks
    private FaultDiagnosisChatService chatService;

    @Test
    void buildsCommandUsingOnlyAgentKnowledgeBasesAndInputSymptom() {
        ChatRequest request = request(LocalDateTime.of(2026, 7, 1, 10, 0), LocalDateTime.of(2026, 7, 1, 10, 30));
        request.getFaultDiagnosis().setSymptom("逆变器告警");
        AgentVo agent = enabledAgent();
        agent.setKnowledgeIds(List.of(11L, 12L));

        DiagnosisCommand command = chatService.buildCommand(request, agent, 3L, "tenant-a");

        assertEquals("设备A", command.deviceName());
        assertEquals("逆变器A", command.inverterName());
        assertEquals("逆变器告警", command.symptom());
        assertEquals(List.of(11L, 12L), command.knowledgeBaseIds());
        assertEquals(7L, command.context().agentId());
        assertEquals(9L, command.context().sessionId());
        assertEquals(3L, command.context().userId());
        assertEquals("tenant-a", command.context().tenantId());
        assertFalse(command.context().requestId().isBlank());
    }

    @Test
    void usesAgentKnowledgeBasesWhenRequestContainsOtherKnowledgeConfiguration() {
        ChatRequest request = request(LocalDateTime.of(2026, 7, 1, 10, 0), LocalDateTime.of(2026, 7, 1, 10, 30));
        request.setKnowledgeId("999");
        AgentVo agent = enabledAgent();
        agent.setKnowledgeIds(List.of(21L));

        DiagnosisCommand command = chatService.buildCommand(request, agent, 3L, "tenant-a");

        assertEquals(List.of(21L), command.knowledgeBaseIds());
    }

    @Test
    void usesConfiguredDefaultWindowWhenBothTimesAreMissing() {
        when(faultDiagnosisProperties.getTimezone()).thenReturn("Asia/Shanghai");
        when(faultDiagnosisProperties.getDefaultWindowMinutes()).thenReturn(45);
        ChatRequest request = request(null, null);

        DiagnosisCommand command = chatService.buildCommand(request, enabledAgent(), 3L, "tenant-a");

        assertEquals(45, Duration.between(command.startTime(), command.endTime()).toMinutes());
    }

    @Test
    void rejectsOnlyOneTimeBoundary() {
        assertThrows(ServiceException.class, () -> chatService.buildCommand(
            request(LocalDateTime.of(2026, 7, 1, 10, 0), null), enabledAgent(), 3L, "tenant-a"));
    }

    @Test
    void rejectsDisabledWrongScenarioAndWrongExecutionModeAgents() {
        AgentVo disabled = enabledAgent();
        disabled.setStatus("1");
        assertThrows(ServiceException.class, () -> chatService.buildCommand(requestWithTimes(), disabled, 3L, "tenant-a"));

        AgentVo general = enabledAgent();
        general.setScenarioCode(AgentScenarioCode.GENERAL_CHAT.name());
        assertThrows(ServiceException.class, () -> chatService.buildCommand(requestWithTimes(), general, 3L, "tenant-a"));

        AgentVo supervisor = enabledAgent();
        supervisor.setExecutionMode(AgentExecutionMode.SUPERVISOR.name());
        assertThrows(ServiceException.class, () -> chatService.buildCommand(requestWithTimes(), supervisor, 3L, "tenant-a"));
    }

    @Test
    void rendersOnlyActualEvidenceCodesAndSourceDocuments() {
        DiagnosisResult result = result(false, List.of(new EvidenceReference(1L, "EV-REAL")),
            List.of(new DiagnosisObservation("OBS", null, "检测到显式故障码", List.of(), List.of("EV-REAL", "EV-FAKE"))),
            List.of(new CandidateFault("F100", KnowledgeLookupStatus.MATCHED,
                List.of(new FaultKnowledgeEvidence(1L, "doc-1", "故障手册.pdf", "fragment-1", 1, "不得输出的长知识片段")),
                List.of("EV-REAL", "EV-FAKE"))));

        String text = chatService.render(result);

        assertTrue(text.contains("EV-REAL"));
        assertFalse(text.contains("EV-FAKE"));
        assertTrue(text.contains("故障手册.pdf"));
        assertFalse(text.contains("不得输出的长知识片段"));
    }

    @Test
    void doesNotInventEvidenceWhenNoneExistsAndExplainsPartialResult() {
        String text = chatService.render(result(true, List.of(), List.of(), List.of()));

        assertTrue(text.contains("实际证据编号：无"));
        assertFalse(text.contains("EV-001"));
        assertTrue(text.contains("本次结果为降级结果"));
    }

    @Test
    void diagnosePassesBuiltCommandToExistingOrchestrator() {
        when(faultDiagnosisOrchestrator.diagnose(any())).thenReturn(result(false, List.of(), List.of(), List.of()));

        chatService.diagnose(requestWithTimes(), enabledAgent(), 3L, "tenant-a");

        ArgumentCaptor<DiagnosisCommand> captor = ArgumentCaptor.forClass(DiagnosisCommand.class);
        verify(faultDiagnosisOrchestrator).diagnose(captor.capture());
        assertEquals(List.of(8L), captor.getValue().knowledgeBaseIds());
    }

    private static ChatRequest requestWithTimes() {
        return request(LocalDateTime.of(2026, 7, 1, 10, 0), LocalDateTime.of(2026, 7, 1, 10, 30));
    }

    private static ChatRequest request(LocalDateTime start, LocalDateTime end) {
        FaultDiagnosisChatInput input = new FaultDiagnosisChatInput();
        input.setDeviceName("设备A");
        input.setInverterName("逆变器A");
        input.setStartTime(start);
        input.setEndTime(end);
        ChatRequest request = new ChatRequest();
        request.setContent("默认症状");
        request.setSessionId(9L);
        request.setFaultDiagnosis(input);
        return request;
    }

    private static AgentVo enabledAgent() {
        AgentVo agent = new AgentVo();
        agent.setId(7L);
        agent.setStatus("0");
        agent.setScenarioCode(AgentScenarioCode.FAULT_DIAGNOSIS.name());
        agent.setExecutionMode(AgentExecutionMode.DETERMINISTIC.name());
        agent.setKnowledgeIds(List.of(8L));
        return agent;
    }

    private static DiagnosisResult result(boolean partial, List<EvidenceReference> references,
                                          List<DiagnosisObservation> observations, List<CandidateFault> candidates) {
        return new DiagnosisResult("request-1", DiagnosisStatus.NO_EXPLICIT_FAULT, partial, "设备A", "逆变器A",
            LocalDateTime.of(2026, 7, 1, 10, 0), LocalDateTime.of(2026, 7, 1, 10, 30), "症状",
            new DataQualitySummary(10, 8, 1, 0, 2, 0.8D, true), null, List.of(), List.of(), observations,
            candidates, List.of("建议检查连接"), List.of("遥测窗口有限"), references);
    }
}
