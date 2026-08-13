package org.ruoyi.service.fault;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.dto.request.FaultDiagnosisChatInput;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.domain.enums.agent.AgentExecutionMode;
import org.ruoyi.domain.enums.agent.AgentScenarioCode;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.diagnosis.FaultDiagnosisOrchestrator;
import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisObservation;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.evidence.enums.EvidenceType;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.application.FaultCodeKnowledgeQueryService;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.fault.model.FaultKnowledgeAnswerDraft;
import org.ruoyi.service.fault.model.FaultRequestPlan;
import org.ruoyi.service.fault.model.FaultTaskType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("dev")
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
    @Mock private org.ruoyi.fault.telemetry.service.TelemetryQueryService telemetryQueryService;
    @Mock private org.ruoyi.fault.report.OperationReportOrchestrator operationReportOrchestrator;
    @Mock private OperationReportSnapshotService operationReportSnapshotService;
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
    void rendersReadableEvidenceAndHidesInternalAuditSteps() {
        DiagnosisResult result = result(false,
            List.of(new EvidenceReference(1L, "EV-REAL", EvidenceType.TELEMETRY, "遥测记录",
                    "设备A，共8条有效记录，出现 F100", true),
                new EvidenceReference(2L, "EV-INTERNAL", EvidenceType.RULE_RESULT, "结果记录",
                    "诊断结果组装完成", false)),
            List.of(new DiagnosisObservation("OBS", null, "检测到显式故障码", List.of(), List.of("EV-REAL", "EV-FAKE"))),
            List.of(new CandidateFault("F100", FaultCodeType.FAULT, KnowledgeLookupStatus.MATCHED,
                List.of(new FaultKnowledgeEvidence(1L, "doc-1", "故障手册.pdf", "fragment-1", 1, "不得输出的长知识片段")),
                List.of("EV-REAL"))));

        String text = chatService.render(result);

        assertTrue(text.contains("遥测记录（EV-REAL）：设备A，共8条有效记录，出现 F100"));
        assertFalse(text.contains("EV-FAKE"));
        assertFalse(text.contains("EV-INTERNAL"));
        assertTrue(text.contains("故障手册.pdf"));
        assertFalse(text.contains("不得输出的长知识片段"));
    }

    @Test
    void doesNotInventEvidenceWhenNoneExistsAndExplainsPartialResult() {
        String text = chatService.render(result(true, List.of(), List.of(), List.of()));

        assertTrue(text.contains("本次没有可引用的持久化证据"));
        assertFalse(text.contains("EV-001"));
        assertTrue(text.contains("本次结果为降级结果"));
    }

    @Test
    void answerBodyDoesNotExposeInternalFields() {
        String text = chatService.render(result(true, List.of(), List.of(), List.of()));

        assertFalse(text.contains("request-1"));
        assertFalse(text.contains("NO_EXPLICIT_FAULT"));
        assertFalse(text.contains("partial"));
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
        when(faultAnswerGenerator.generateKnowledgeDraft(any(), any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("model unavailable"));

        LoggedAnswer result = diagnoseWithLogs(input);
        String answer = result.answer();

        assertTrue(answer.contains("### F07561：驱动编码器多圈线数不是二的幂次方。"));
        assertTrue(answer.contains("**可能原因**"));
        assertTrue(answer.contains("参数 p0421 设置错误"));
        assertTrue(answer.contains("**处理建议**"));
        assertFalse(answer.contains("知识正文："));
        assertTrue(answer.contains("来源：F07561 - S120_故障手册.pdf / fragment-7"));
        assertTrue(answer.contains("本次仅查询故障手册，未读取设备遥测数据"));
        assertFallbackLog(result.logs(), "MODEL_EXCEPTION");
    }

    @Test
    void returnsCompleteKnowledgeWhenModelAnswerFailsSafetyValidation() {
        FaultKnowledgeQuery query = new FaultKnowledgeQuery("F07561", List.of(21L));
        FaultKnowledgeEvidence evidence = new FaultKnowledgeEvidence(21L, "doc-1", "S120_故障手册.pdf",
            "fragment-7", 7, "原因：参数 p0421 设置错误。\n处理建议：检查参数设定。");
        PureKnowledgeRequest input = pureKnowledgeRequest(FaultKnowledgeResult.matched(query, List.of(evidence)));
        when(faultAnswerGenerator.generateKnowledgeDraft(any(), any(), any(), any(), any()))
            .thenReturn(knowledgeDraft("不安全的模型回答"));
        when(faultAnswerSafetyValidator.valid(any(), any(), anyBoolean())).thenReturn(false);

        LoggedAnswer result = diagnoseWithLogs(input);
        String answer = result.answer();

        assertTrue(answer.contains("**可能原因**"));
        assertTrue(answer.contains("参数 p0421 设置错误"));
        assertFalse(answer.contains("不安全的模型回答"));
        assertTrue(answer.contains("S120_故障手册.pdf"));
        assertFallbackLog(result.logs(), "SAFETY_VALIDATION_REJECTED");
    }

    @Test
    void returnsCompleteKnowledgeWhenModelAnswerIsEmpty() {
        FaultKnowledgeQuery query = new FaultKnowledgeQuery("F07561", List.of(21L));
        FaultKnowledgeEvidence evidence = new FaultKnowledgeEvidence(21L, "doc-1", "S120_故障手册.pdf",
            "fragment-7", 7, "原因：参数 p0421 设置错误。\n处理建议：检查参数设定。");
        PureKnowledgeRequest input = pureKnowledgeRequest(FaultKnowledgeResult.matched(query, List.of(evidence)));
        when(faultAnswerGenerator.generateKnowledgeDraft(any(), any(), any(), any(), any())).thenReturn(null);

        LoggedAnswer result = diagnoseWithLogs(input);
        String answer = result.answer();

        assertTrue(answer.contains("**可能原因**"));
        assertTrue(answer.contains("参数 p0421 设置错误"));
        assertTrue(answer.contains("S120_故障手册.pdf"));
        verifyNoInteractions(faultAnswerSafetyValidator);
        assertFallbackLog(result.logs(), "EMPTY_MODEL_ANSWER");
    }

    @Test
    void usesSafeModelAnswerAndKeepsKnowledgeSource() {
        FaultKnowledgeQuery query = new FaultKnowledgeQuery("F07561", List.of(21L));
        FaultKnowledgeEvidence evidence = new FaultKnowledgeEvidence(21L, "doc-1", "S120_故障手册.pdf",
            "fragment-7", 7, "原因：参数 p0421 设置错误。\n处理建议：检查参数设定。");
        PureKnowledgeRequest input = pureKnowledgeRequest(FaultKnowledgeResult.matched(query, List.of(evidence)));
        when(faultAnswerGenerator.generateKnowledgeDraft(any(), any(), any(), any(), any()))
            .thenReturn(knowledgeDraft("参数设置不正确会触发该故障。"));
        when(faultAnswerSafetyValidator.valid(any(), any(), anyBoolean())).thenReturn(true);

        String answer = chatService.diagnose(input.request(), input.agent(), input.model(), 3L, "tenant-a");

        assertTrue(answer.contains("参数设置不正确会触发该故障。"));
        assertTrue(answer.contains("**先检查什么**"));
        assertTrue(answer.contains("**处理建议**"));
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

    @Test
    void resolvesBlankInverterFromTelemetryBeforeDiagnosing() {
        FaultRequestPlan plan = new FaultRequestPlan(List.of(FaultTaskType.DIAGNOSE), "设备A", null, null,
            null, null, List.of(), "症状", List.of());
        when(faultDiagnosisProperties.getTimezone()).thenReturn("Asia/Shanghai");
        when(faultDiagnosisProperties.getDefaultWindowMinutes()).thenReturn(30);
        when(faultRequestPlanner.plan(any(), any(), any(), any(), anyInt(), any(), any(), any())).thenReturn(plan);
        when(telemetryQueryService.resolveInverterName("设备A")).thenReturn("逆变器A");
        when(faultDiagnosisOrchestrator.diagnose(any())).thenReturn(result(false, List.of(), List.of(), List.of()));
        ChatRequest request = new ChatRequest();
        request.setContent("设备A 现在运行状态如何？");
        request.setSessionId(9L);

        chatService.diagnose(request, enabledAgent(), org.mockito.Mockito.mock(ChatModel.class), 3L, "tenant-a");

        ArgumentCaptor<DiagnosisCommand> captor = ArgumentCaptor.forClass(DiagnosisCommand.class);
        verify(faultDiagnosisOrchestrator).diagnose(captor.capture());
        assertEquals("设备A", captor.getValue().deviceName());
        assertEquals("逆变器A", captor.getValue().inverterName());
    }

    @Test
    void clarifiesWhenDeviceHasMultipleInvertersInsteadOfDiagnosing() {
        FaultRequestPlan plan = new FaultRequestPlan(List.of(FaultTaskType.DIAGNOSE), "设备A", null, null,
            null, null, List.of(), "症状", List.of());
        when(faultDiagnosisProperties.getTimezone()).thenReturn("Asia/Shanghai");
        when(faultRequestPlanner.plan(any(), any(), any(), any(), anyInt(), any(), any(), any())).thenReturn(plan);
        when(telemetryQueryService.resolveInverterName("设备A"))
            .thenThrow(new ServiceException("设备 设备A 下存在多个逆变器（逆变器1、逆变器2），请在问题中指明要诊断的逆变器"));
        ChatRequest request = new ChatRequest();
        request.setContent("设备A 现在运行状态如何？");
        request.setSessionId(9L);

        String answer = chatService.diagnose(request, enabledAgent(), org.mockito.Mockito.mock(ChatModel.class), 3L, "tenant-a");

        assertEquals("设备 设备A 下存在多个逆变器（逆变器1、逆变器2），请在问题中指明要诊断的逆变器", answer);
        verify(faultDiagnosisOrchestrator, never()).diagnose(any());
    }

    @Test
    void generateReportTaskReturnsShortConclusionPersistsSnapshotAndSkipsAnswerGeneration() {
        FaultRequestPlan plan = new FaultRequestPlan(List.of(FaultTaskType.GENERATE_REPORT), "设备A", null, null,
            null, null, List.of(), null, List.of());
        when(faultDiagnosisProperties.getTimezone()).thenReturn("Asia/Shanghai");
        when(faultDiagnosisProperties.getDefaultWindowMinutes()).thenReturn(30);
        when(faultRequestPlanner.plan(any(), any(), any(), any(), anyInt(), any(), any(), any())).thenReturn(plan);
        when(telemetryQueryService.resolveInverterName("设备A")).thenReturn("逆变器A");
        when(operationReportOrchestrator.generate(any())).thenReturn(reportResult());
        ChatRequest request = new ChatRequest();
        request.setContent("生成设备A今天的运行报告");
        request.setSessionId(9L);

        String answer = chatService.diagnose(request, enabledAgent(), org.mockito.Mockito.mock(ChatModel.class), 3L, "tenant-a");

        assertEquals("运行报告已生成。报告周期内设备状态：关注。", answer);
        verify(operationReportOrchestrator).generate(any());
        verify(operationReportSnapshotService).save(any(), eq(9L), eq(3L), eq("tenant-a"));
        verify(faultDiagnosisOrchestrator, never()).diagnose(any());
        verifyNoInteractions(faultAnswerGenerator, faultAnswerSafetyValidator);
    }

    private static org.ruoyi.fault.report.OperationReportResult reportResult() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 4, 0, 0);
        LocalDateTime end = start.plusDays(1);
        LocalDateTime alarmStart = start.plusHours(1);
        org.ruoyi.fault.telemetry.model.OperationStatistics operation =
            new org.ruoyi.fault.telemetry.model.OperationStatistics(null, null, List.of(),
                List.of(new org.ruoyi.fault.telemetry.model.CodeOccurrence("A07089", 30,
                    alarmStart, alarmStart.plusMinutes(2))));
        org.ruoyi.fault.telemetry.model.TelemetryQueryResult telemetry =
            new org.ruoyi.fault.telemetry.model.TelemetryQueryResult("设备A", start, end,
                new DataQualitySummary(10, 10, 0, 0, 0, 1D, true), List.of(), List.of("A07089"), List.of(),
                List.of(new org.ruoyi.fault.telemetry.model.StatusEvent(alarmStart, "42", null, "A07089"),
                    new org.ruoyi.fault.telemetry.model.StatusEvent(alarmStart.plusMinutes(3), "0", null, null)),
                new org.ruoyi.fault.telemetry.model.TelemetryStatistics(10, null, null, null, null, null, null,
                    null, null, null, null, null),
                "sha256-digest", false, end.minusMinutes(1), List.of(), operation);
        DiagnosisResult diagnosis = new DiagnosisResult("request", DiagnosisStatus.WARNING_DETECTED, false,
            "设备A", "逆变器A", start, end, start, end, false, end.minusMinutes(1), null,
            new DataQualitySummary(10, 10, 0, 0, 0, 1D, true),
            new org.ruoyi.fault.telemetry.model.TelemetryStatistics(10, null, null, null, null, null, null,
                null, null, null, null, null),
            List.of(), List.of("A07089"), List.of(), List.of(),
            List.of(new CandidateFault("A07089", FaultCodeType.ALARM, KnowledgeLookupStatus.MATCHED,
                List.of(new FaultKnowledgeEvidence(7L, "doc", "G120故障手册", "fragment", 0, "直流回路电压异常")),
                List.of("EV-002"))),
            List.of("检查供电电压"), List.of(), List.of());
        return org.ruoyi.fault.report.OperationReportResult.fromSources("RP-1", "设备A", "逆变器A", start, end,
            end.plusSeconds(30), org.ruoyi.fault.report.ReportHealthStatus.ATTENTION,
            new org.ruoyi.fault.report.OperationReportResult.Summary("报告周期内设备状态：关注。", List.of(),
                List.of("A07089"), true), telemetry, null, null, diagnosis);
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

    private static FaultKnowledgeAnswerDraft knowledgeDraft(String summary) {
        return new FaultKnowledgeAnswerDraft(List.of(new FaultKnowledgeAnswerDraft.FaultAnswer(
            "F07561", summary, List.of("参数 p0421 设置错误。"), "检查 p0421 的参数设定。",
            List.of(), List.of("检查参数设定。"), List.of("p0421"), List.of())));
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

    private LoggedAnswer diagnoseWithLogs(PureKnowledgeRequest input) {
        return captureLogs(() -> chatService.diagnose(
            input.request(), input.agent(), input.model(), 3L, "tenant-a"));
    }

    private static LoggedAnswer captureLogs(Supplier<String> invocation) {
        Logger logger = (Logger) LoggerFactory.getLogger(FaultDiagnosisChatService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String answer = invocation.get();
            List<String> logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            return new LoggedAnswer(answer, logs);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static void assertFallbackLog(List<String> logs, String reason) {
        String message = logs.stream()
            .filter(item -> item.contains("fallbackReason=" + reason))
            .findFirst()
            .orElseThrow(() -> new AssertionError("未找到降级日志: " + reason));
        assertTrue(message.matches(".*requestId=[^,]+,.*"));
        assertTrue(message.contains("taskType=[EXPLAIN_FAULT_CODE]"));
        assertTrue(message.contains("faultCode=[F07561]"));
        assertTrue(message.matches(".*elapsedMs=\\d+,.*"));
        assertFalse(message.contains("参数 p0421"));
    }

    private record PureKnowledgeRequest(ChatRequest request, AgentVo agent, ChatModel model) {
    }

    private record LoggedAnswer(String answer, List<String> logs) {
    }

    private static DiagnosisResult result(boolean partial, List<EvidenceReference> references,
                                          List<DiagnosisObservation> observations, List<CandidateFault> candidates) {
        return new DiagnosisResult("request-1", DiagnosisStatus.NO_EXPLICIT_FAULT, partial, "设备A", "逆变器A",
            LocalDateTime.of(2026, 7, 1, 10, 0), LocalDateTime.of(2026, 7, 1, 10, 30),
            LocalDateTime.of(2026, 7, 1, 10, 0), LocalDateTime.of(2026, 7, 1, 10, 30),
            false, LocalDateTime.of(2026, 7, 1, 10, 29), "症状",
            new DataQualitySummary(10, 8, 1, 0, 2, 0.8D, true), null, List.of(), List.of(), List.of(),
            observations, candidates, List.of("建议检查连接"), List.of("遥测窗口有限"), references);
    }
}
