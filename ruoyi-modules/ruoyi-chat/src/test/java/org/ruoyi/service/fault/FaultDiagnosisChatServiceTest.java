package org.ruoyi.service.fault;

import org.junit.jupiter.api.Test;
import dev.langchain4j.model.chat.ChatModel;
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
import org.ruoyi.fault.application.FaultCodeKnowledgeQueryService;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.fault.model.FaultRequestPlan;
import org.ruoyi.service.fault.model.FaultTaskType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class FaultDiagnosisChatServiceTest {

    @Mock
    private FaultDiagnosisOrchestrator faultDiagnosisOrchestrator;
    @Mock
    private FaultDiagnosisProperties faultDiagnosisProperties;
    @Mock private FaultRequestPlanner faultRequestPlanner;
    @Mock private FaultAnswerGenerator faultAnswerGenerator;
    @Mock private FaultAnswerSafetyValidator faultAnswerSafetyValidator;
    @Mock private FaultCodeKnowledgeQueryService faultCodeKnowledgeQueryService;
    @Mock private IChatMessageService chatMessageService;
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

    @Test
    void pureFaultCodeQueryUsesPlannerAndNeverQueriesTelemetry() {
        FaultKnowledgeQuery query = new FaultKnowledgeQuery("F30005", List.of(21L));
        PureKnowledgeRequest input = pureKnowledgeRequest(FaultKnowledgeResult.notFound(query));

        String answer = chatService.diagnose(input.request(), input.agent(), input.model(), 3L, "tenant-a");

        verify(faultRequestPlanner).plan(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(faultDiagnosisOrchestrator, never()).diagnose(any());
        verify(faultCodeKnowledgeQueryService).query("F30005", List.of(21L));
        verifyNoInteractions(faultAnswerGenerator, faultAnswerSafetyValidator);
        assertTrue(answer.contains("故障码知识查询：F30005"));
        assertTrue(answer.contains("已查询绑定的故障知识库，但未找到与该故障码精确匹配的内容"));
        assertTrue(answer.contains("本次仅查询故障手册，未读取设备遥测数据"));
        assertFalse(answer.contains("确定性诊断事实"));
        assertFalse(answer.contains("诊断状态：未执行设备遥测诊断"));
    }

    @Test
    void returnsCompleteKnowledgeAndSourceWhenModelFails() {
        FaultKnowledgeQuery query = new FaultKnowledgeQuery("F07561", List.of(21L));
        FaultKnowledgeEvidence evidence = new FaultKnowledgeEvidence(21L, "doc-1", "S120_故障手册.pdf",
            "fragment-7", 7, "含义：驱动编码器多圈线数不是二的幂次方。\n原因：参数 p0421 设置错误。\n处理建议：检查参数设定。");
        PureKnowledgeRequest input = pureKnowledgeRequest(FaultKnowledgeResult.matched(query, List.of(evidence)));
        when(faultAnswerGenerator.generate(any(), any(), any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("model unavailable"));

        String answer = chatService.diagnose(input.request(), input.agent(), input.model(), 3L, "tenant-a");

        assertTrue(answer.contains(evidence.content()));
        assertTrue(answer.contains("来源：F07561 - S120_故障手册.pdf / fragment-7"));
        assertTrue(answer.contains("本次仅查询故障手册，未读取设备遥测数据"));
    }

    @Test
    void returnsCompleteKnowledgeWhenModelAnswerFailsSafetyValidation() {
        FaultKnowledgeQuery query = new FaultKnowledgeQuery("F07561", List.of(21L));
        FaultKnowledgeEvidence evidence = new FaultKnowledgeEvidence(21L, "doc-1", "S120_故障手册.pdf",
            "fragment-7", 7, "原因：参数 p0421 设置错误。\n处理建议：检查参数设定。");
        PureKnowledgeRequest input = pureKnowledgeRequest(FaultKnowledgeResult.matched(query, List.of(evidence)));
        when(faultAnswerGenerator.generate(any(), any(), any(), any(), any(), any())).thenReturn("不安全的模型回答");
        when(faultAnswerSafetyValidator.valid(any(), any(), anyBoolean())).thenReturn(false);

        String answer = chatService.diagnose(input.request(), input.agent(), input.model(), 3L, "tenant-a");

        assertTrue(answer.contains(evidence.content()));
        assertFalse(answer.contains("不安全的模型回答"));
        assertTrue(answer.contains("S120_故障手册.pdf"));
    }

    @Test
    void returnsCompleteKnowledgeWhenModelAnswerIsEmpty() {
        FaultKnowledgeQuery query = new FaultKnowledgeQuery("F07561", List.of(21L));
        FaultKnowledgeEvidence evidence = new FaultKnowledgeEvidence(21L, "doc-1", "S120_故障手册.pdf",
            "fragment-7", 7, "原因：参数 p0421 设置错误。\n处理建议：检查参数设定。");
        PureKnowledgeRequest input = pureKnowledgeRequest(FaultKnowledgeResult.matched(query, List.of(evidence)));
        when(faultAnswerGenerator.generate(any(), any(), any(), any(), any(), any())).thenReturn(" ");

        String answer = chatService.diagnose(input.request(), input.agent(), input.model(), 3L, "tenant-a");

        assertTrue(answer.contains(evidence.content()));
        assertTrue(answer.contains("S120_故障手册.pdf"));
        verifyNoInteractions(faultAnswerSafetyValidator);
    }

    @Test
    void usesSafeModelAnswerAndKeepsKnowledgeSource() {
        FaultKnowledgeQuery query = new FaultKnowledgeQuery("F07561", List.of(21L));
        FaultKnowledgeEvidence evidence = new FaultKnowledgeEvidence(21L, "doc-1", "S120_故障手册.pdf",
            "fragment-7", 7, "原因：参数 p0421 设置错误。\n处理建议：检查参数设定。");
        PureKnowledgeRequest input = pureKnowledgeRequest(FaultKnowledgeResult.matched(query, List.of(evidence)));
        when(faultAnswerGenerator.generate(any(), any(), any(), any(), any(), any()))
            .thenReturn("F07561 的原因是参数设置错误，建议检查参数。");
        when(faultAnswerSafetyValidator.valid(any(), any(), anyBoolean())).thenReturn(true);

        String answer = chatService.diagnose(input.request(), input.agent(), input.model(), 3L, "tenant-a");

        assertTrue(answer.contains("F07561 的原因是参数设置错误，建议检查参数。"));
        assertTrue(answer.contains("来源：F07561 - S120_故障手册.pdf / fragment-7"));
        assertTrue(answer.contains("本次仅查询故障手册，未读取设备遥测数据"));
    }

    @Test
    void distinguishesKnowledgeQueryFailureFromNotFound() {
        FaultKnowledgeQuery query = new FaultKnowledgeQuery("F30005", List.of(21L));
        PureKnowledgeRequest input = pureKnowledgeRequest(FaultKnowledgeResult.failed(query));

        String answer = chatService.diagnose(input.request(), input.agent(), input.model(), 3L, "tenant-a");

        assertTrue(answer.contains("知识查询失败，请稍后重试"));
        assertFalse(answer.contains("未找到与该故障码精确匹配的内容"));
        verifyNoInteractions(faultAnswerGenerator, faultAnswerSafetyValidator);
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

    private PureKnowledgeRequest pureKnowledgeRequest(FaultKnowledgeResult result) {
        when(faultDiagnosisProperties.getTimezone()).thenReturn("Asia/Shanghai");
        when(faultDiagnosisProperties.getDefaultWindowMinutes()).thenReturn(30);
        ChatRequest request = new ChatRequest();
        request.setContent(result.faultCode() + " 是什么原因？如何解决？");
        request.setSessionId(9L);
        AgentVo agent = enabledAgent();
        agent.setKnowledgeIds(List.of(21L));
        ChatModel model = org.mockito.Mockito.mock(ChatModel.class);
        FaultRequestPlan plan = new FaultRequestPlan(List.of(FaultTaskType.EXPLAIN_FAULT_CODE), null, null, null,
            null, null, List.of(result.faultCode()), null, List.of());
        when(faultRequestPlanner.plan(any(), any(), any(), any(), anyInt(), any(), any(), any())).thenReturn(plan);
        when(faultCodeKnowledgeQueryService.query(result.faultCode(), List.of(21L))).thenReturn(result);
        return new PureKnowledgeRequest(request, agent, model);
    }

    private record PureKnowledgeRequest(ChatRequest request, AgentVo agent, ChatModel model) {
    }

    private static DiagnosisResult result(boolean partial, List<EvidenceReference> references,
                                          List<DiagnosisObservation> observations, List<CandidateFault> candidates) {
        return new DiagnosisResult("request-1", DiagnosisStatus.NO_EXPLICIT_FAULT, partial, "设备A", "逆变器A",
            LocalDateTime.of(2026, 7, 1, 10, 0), LocalDateTime.of(2026, 7, 1, 10, 30), "症状",
            new DataQualitySummary(10, 8, 1, 0, 2, 0.8D, true), null, List.of(), List.of(), observations,
            candidates, List.of("建议检查连接"), List.of("遥测窗口有限"), references);
    }
}
