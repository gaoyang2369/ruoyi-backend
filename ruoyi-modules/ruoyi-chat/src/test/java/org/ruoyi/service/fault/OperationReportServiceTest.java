package org.ruoyi.service.fault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.domain.bo.fault.OperationReportGenerateBo;
import org.ruoyi.domain.enums.agent.AgentExecutionMode;
import org.ruoyi.domain.enums.agent.AgentScenarioCode;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.report.OperationReportOrchestrator;
import org.ruoyi.fault.telemetry.service.TelemetryQueryService;
import org.ruoyi.service.agent.IAgentService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 REST 运行报告入口的 Agent 校验、逆变器补全与时间窗处理。 */
@ExtendWith(MockitoExtension.class)
class OperationReportServiceTest {

    @Mock
    private IAgentService agentService;
    @Mock
    private TelemetryQueryService telemetryQueryService;
    @Mock
    private OperationReportOrchestrator operationReportOrchestrator;
    @Mock
    private OperationReportNarrator operationReportNarrator;
    @Mock
    private org.ruoyi.common.chat.service.chat.IChatModelService chatModelService;
    @Mock
    private org.ruoyi.factory.ChatServiceFactory chatServiceFactory;

    private OperationReportService service;

    @BeforeEach
    void setUp() {
        service = new OperationReportService(agentService, telemetryQueryService,
            operationReportOrchestrator, operationReportNarrator, chatModelService, chatServiceFactory,
            new FaultDiagnosisProperties());
    }

    @Test
    void buildsCommandFromAgentKnowledgeBasesAndExplicitWindow() {
        when(agentService.queryById(7L)).thenReturn(enabledAgent());

        OperationReportGenerateBo bo = bo("设备A", "逆变器A",
            LocalDateTime.of(2026, 8, 4, 0, 0), LocalDateTime.of(2026, 8, 4, 1, 0));
        service.generate(bo, 3L, "tenant");

        ArgumentCaptor<DiagnosisCommand> captor = ArgumentCaptor.forClass(DiagnosisCommand.class);
        verify(operationReportOrchestrator).generate(captor.capture());
        DiagnosisCommand command = captor.getValue();
        assertEquals("设备A", command.deviceName());
        assertEquals("逆变器A", command.inverterName());
        assertEquals(List.of(9L, 10L), command.knowledgeBaseIds());
        assertNull(command.symptom());
        assertNull(command.context().sessionId());
        assertEquals(3L, command.context().userId());
        assertEquals("tenant", command.context().tenantId());
        verify(telemetryQueryService, never()).resolveInverterName(any());
    }

    @Test
    void resolvesInverterWhenBlank() {
        when(agentService.queryById(7L)).thenReturn(enabledAgent());
        when(telemetryQueryService.resolveInverterName("设备A")).thenReturn("逆变器B");

        service.generate(bo("设备A", null, LocalDateTime.of(2026, 8, 4, 0, 0),
            LocalDateTime.of(2026, 8, 4, 1, 0)), 3L, "tenant");

        ArgumentCaptor<DiagnosisCommand> captor = ArgumentCaptor.forClass(DiagnosisCommand.class);
        verify(operationReportOrchestrator).generate(captor.capture());
        assertEquals("逆变器B", captor.getValue().inverterName());
    }

    @Test
    void usesDefaultWindowWhenTimesAreBothNull() {
        when(agentService.queryById(7L)).thenReturn(enabledAgent());

        service.generate(bo("设备A", "逆变器A", null, null), 3L, "tenant");

        ArgumentCaptor<DiagnosisCommand> captor = ArgumentCaptor.forClass(DiagnosisCommand.class);
        verify(operationReportOrchestrator).generate(captor.capture());
        DiagnosisCommand command = captor.getValue();
        assertEquals(Duration.ofMinutes(30), Duration.between(command.startTime(), command.endTime()));
    }

    @Test
    void rejectsPartialTimeWindow() {
        when(agentService.queryById(7L)).thenReturn(enabledAgent());

        assertThrows(ServiceException.class, () -> service.generate(
            bo("设备A", "逆变器A", LocalDateTime.of(2026, 8, 4, 0, 0), null), 3L, "tenant"));
    }

    @Test
    void rejectsDisabledAgent() {
        AgentVo agent = enabledAgent();
        agent.setStatus("1");
        when(agentService.queryById(7L)).thenReturn(agent);

        assertThrows(ServiceException.class, () -> service.generate(
            bo("设备A", "逆变器A", null, null), 3L, "tenant"));
    }

    @Test
    void narrateFallsBackWhenAgentHasNoModel() {
        when(agentService.queryById(7L)).thenReturn(enabledAgent());

        assertNull(service.narrate(7L, null));

        verify(chatServiceFactory, never()).getOriginalService(any());
    }

    @Test
    void rejectsNonFaultDiagnosisAgent() {
        AgentVo agent = enabledAgent();
        agent.setScenarioCode(AgentScenarioCode.GENERAL_CHAT.name());
        when(agentService.queryById(7L)).thenReturn(agent);

        assertThrows(ServiceException.class, () -> service.generate(
            bo("设备A", "逆变器A", null, null), 3L, "tenant"));
    }

    private static OperationReportGenerateBo bo(String deviceName, String inverterName,
                                                LocalDateTime startTime, LocalDateTime endTime) {
        OperationReportGenerateBo bo = new OperationReportGenerateBo();
        bo.setAgentId(7L);
        bo.setDeviceName(deviceName);
        bo.setInverterName(inverterName);
        bo.setStartTime(startTime);
        bo.setEndTime(endTime);
        return bo;
    }

    private static AgentVo enabledAgent() {
        AgentVo agent = new AgentVo();
        agent.setId(7L);
        agent.setStatus("0");
        agent.setScenarioCode(AgentScenarioCode.FAULT_DIAGNOSIS.name());
        agent.setExecutionMode(AgentExecutionMode.DETERMINISTIC.name());
        agent.setKnowledgeIds(List.of(9L, 10L));
        return agent;
    }

}
