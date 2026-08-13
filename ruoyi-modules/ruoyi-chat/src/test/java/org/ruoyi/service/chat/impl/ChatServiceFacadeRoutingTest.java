package org.ruoyi.service.chat.impl;

import org.junit.jupiter.api.AfterEach;
import cn.hutool.extra.spring.SpringUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.chat.base.ThreadContext;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.dto.request.WorkFlowRunner;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.chat.service.workFlow.IWorkFlowStarterService;
import org.ruoyi.common.sse.core.SseEmitterManager;
import org.ruoyi.common.sse.dto.SseEventDto;
import org.ruoyi.common.sse.utils.SseMessageUtils;
import org.ruoyi.domain.enums.agent.AgentExecutionMode;
import org.ruoyi.domain.enums.agent.AgentScenarioCode;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.factory.ChatServiceFactory;
import org.ruoyi.mcp.service.core.LangChain4jMcpToolProviderService;
import org.ruoyi.mcp.service.core.ToolProviderFactory;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.chat.hermes.HermesChatClient;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesChatResult;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesStream;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesToolProgress;
import org.ruoyi.service.fault.FaultDiagnosisChatService;
import org.ruoyi.service.fault.FaultRequestPlanner;
import org.ruoyi.service.fault.model.FaultReportAttachment;
import org.ruoyi.service.fault.model.FaultReportChatResult;
import org.ruoyi.service.fault.model.FaultRequestPlan;
import org.ruoyi.service.fault.model.FaultTaskType;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.service.vector.VectorStoreService;
import org.ruoyi.websocket.chat.sync.ChatSyncEvent;
import org.ruoyi.websocket.chat.sync.ChatSyncEventPublisher;
import org.ruoyi.websocket.chat.sync.ChatSyncEventType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;
import java.util.Arrays;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("dev")
class ChatServiceFacadeRoutingTest {

    @BeforeAll
    static void disableSseStaticUtilityBeforeInitialization() {
        ApplicationContext context = org.mockito.Mockito.mock(ApplicationContext.class);
        Environment environment = org.mockito.Mockito.mock(Environment.class);
        when(context.getEnvironment()).thenReturn(environment);
        when(environment.getProperty("sse.enabled", Boolean.class, true)).thenReturn(false);
        new SpringUtil().setApplicationContext(context);
        SseMessageUtils.isEnable();
    }

    @Mock private IChatModelService chatModelService;
    @Mock private ChatServiceFactory chatServiceFactory;
    @Mock private IKnowledgeInfoService knowledgeInfoService;
    @Mock private VectorStoreService vectorStoreService;
    @Mock private KnowledgeRetrievalService knowledgeRetrievalService;
    @Mock private SseEmitterManager sseEmitterManager;
    @Mock private IChatMessageService chatMessageService;
    @Mock private IWorkFlowStarterService workFlowStarterService;
    @Mock private ToolProviderFactory toolProviderFactory;
    @Mock private IAgentService agentService;
    @Mock private LangChain4jMcpToolProviderService langChain4jMcpToolProviderService;
    @Mock private HermesChatClient hermesChatClient;
    @Mock private FaultRequestPlanner faultRequestPlanner;
    @Mock private FaultDiagnosisChatService faultDiagnosisChatService;
    @Mock private FaultDiagnosisProperties faultDiagnosisProperties;
    @Mock private HermesStream hermesStream;
    @Mock private ChatSyncEventPublisher chatSyncEventPublisher;

    @AfterEach
    void clearThreadContext() {
        ThreadContext.unload();
    }

    @Test
    void faultDiagnosisUsesHermesAndSkipsGeneralKnowledgeRag() throws Exception {
        ChatServiceFacade facade = facade();
        ChatRequest request = request();
        AgentVo faultAgent = faultAgent();
        faultAgent.setSystemPrompt("只回答故障诊断问题");
        request.setContent("本次问题");
        when(hermesChatClient.modelName()).thenReturn("fault");
        when(chatMessageService.getMessagesBySessionIdAndUserId(2L, 1L, 20))
            .thenReturn(List.of(UserMessage.from("历史问题"), AiMessage.from("历史回答")));
        when(hermesChatClient.open(any())).thenReturn(hermesStream);
        when(hermesStream.consume(any())).thenReturn(new HermesChatResult("Hermes 诊断结果"));

        assertSame(request.getEmitter(), facade.handleSpecialChatModes(request, faultAgent, "tenant-a"));

        verify(hermesStream, timeout(1_000)).consume(any());
        verify(knowledgeInfoService, never()).queryById(any());
        verify(chatModelService, never()).selectModelByName(any());
        verify(chatMessageService, timeout(1_000)).saveChatMessage(1L, 2L, "Hermes 诊断结果", "assistant", "fault");
        ArgumentCaptor<List<HermesChatClient.HermesMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(hermesChatClient).open(messages.capture());
        assertEquals(List.of("只回答故障诊断问题", "历史问题", "历史回答", "本次问题"),
            messages.getValue().stream().map(HermesChatClient.HermesMessage::content).toList());
        assertEquals(List.of("system", "user", "assistant", "user"),
            messages.getValue().stream().map(HermesChatClient.HermesMessage::role).toList());
        assertEquals("user", messages.getValue().get(messages.getValue().size() - 1).role());
    }

    @Test
    void explicitReportUsesDeterministicServiceAndSkipsHermes() {
        ChatServiceFacade facade = facade();
        ChatRequest request = request(2L, "请生成G120电机1最近30分钟运行报告");
        AgentVo agent = faultAgent();
        FaultRequestPlan plan = new FaultRequestPlan(List.of(FaultTaskType.GENERATE_REPORT),
            "G120电机1", null, 30, null, null, List.of(), null, List.of());
        FaultReportAttachment attachment = new FaultReportAttachment("RP-1", "G120电机1运行报告",
            "G120电机1", "INV-1", LocalDateTime.of(2026, 8, 13, 10, 0),
            LocalDateTime.of(2026, 8, 13, 10, 30), "COMPLETED", "NORMAL", "ATTENTION", 0.95D);
        when(hermesChatClient.modelName()).thenReturn("fault");
        when(faultRequestPlanner.planExplicitReportRequest(any(), any())).thenReturn(Optional.of(plan));
        when(faultDiagnosisChatService.generateReport(request, agent, plan, 1L, "tenant-a"))
            .thenReturn(new FaultReportChatResult("运行报告已生成。", attachment));

        assertSame(request.getEmitter(), facade.handleSpecialChatModes(request, agent, "tenant-a"));

        verify(faultDiagnosisChatService, timeout(1_000)).generateReport(request, agent, plan, 1L, "tenant-a");
        verify(hermesChatClient, never()).open(any());
        verify(chatMessageService, timeout(1_000)).saveChatMessage(
            1L, 2L, "运行报告已生成。", "assistant", "fault");
    }

    @Test
    void chatRequestDefaultsToWebSource() {
        assertEquals("WEB", new ChatRequest().getSource());
    }

    @Test
    void secondTurnOfSameSessionSendsFirstUserAndAssistantAsHermesHistory() {
        ChatServiceFacade facade = facade();
        AgentVo agent = faultAgent();
        HermesStream firstStream = org.mockito.Mockito.mock(HermesStream.class);
        HermesStream secondStream = org.mockito.Mockito.mock(HermesStream.class);
        ChatRequest firstTurn = request(2L, "第一轮用户问题");
        ChatRequest secondTurn = request(2L, "第二轮用户问题");

        when(hermesChatClient.modelName()).thenReturn("fault");
        // The first query sees no prior messages; after the normal first completion, RuoYi persistence is
        // represented by the history returned for the second request.
        when(chatMessageService.getMessagesBySessionIdAndUserId(2L, 1L, 20)).thenReturn(
            List.of(), List.of(UserMessage.from("第一轮用户问题"), AiMessage.from("第一轮助手回复")));
        when(hermesChatClient.open(any())).thenReturn(firstStream, secondStream);
        when(firstStream.consume(any())).thenReturn(new HermesChatResult("第一轮助手回复"));
        when(secondStream.consume(any())).thenReturn(new HermesChatResult("第二轮助手回复"));

        facade.handleSpecialChatModes(firstTurn, agent, "tenant-a");
        verify(firstStream, timeout(1_000)).consume(any());
        facade.handleSpecialChatModes(secondTurn, agent, "tenant-a");
        verify(secondStream, timeout(1_000)).consume(any());

        ArgumentCaptor<List<HermesChatClient.HermesMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(hermesChatClient, times(2)).open(messages.capture());
        assertEquals(List.of("第一轮用户问题"), contents(messages.getAllValues().get(0)));
        assertEquals(List.of("第一轮用户问题", "第一轮助手回复", "第二轮用户问题"),
            contents(messages.getAllValues().get(1)));
        assertEquals("user", last(messages.getAllValues().get(1)).role());
        assertEquals("第二轮用户问题", last(messages.getAllValues().get(1)).content());
        verify(chatMessageService, timeout(1_000)).saveChatMessage(
            1L, 2L, "第一轮助手回复", "assistant", "fault");
    }

    @Test
    void differentSessionsUseOnlyTheirOwnHistory() {
        ChatServiceFacade facade = facade();
        HermesStream firstStream = org.mockito.Mockito.mock(HermesStream.class);
        HermesStream secondStream = org.mockito.Mockito.mock(HermesStream.class);
        when(hermesChatClient.modelName()).thenReturn("fault");
        when(chatMessageService.getMessagesBySessionIdAndUserId(2L, 1L, 20))
            .thenReturn(List.of(UserMessage.from("会话一问题"), AiMessage.from("会话一回复")));
        when(chatMessageService.getMessagesBySessionIdAndUserId(3L, 1L, 20))
            .thenReturn(List.of(UserMessage.from("会话二问题"), AiMessage.from("会话二回复")));
        when(hermesChatClient.open(any())).thenReturn(firstStream, secondStream);
        when(firstStream.consume(any())).thenReturn(new HermesChatResult("会话一新回复"));
        when(secondStream.consume(any())).thenReturn(new HermesChatResult("会话二新回复"));

        facade.handleSpecialChatModes(request(2L, "会话一当前问题"), faultAgent(), "tenant-a");
        verify(firstStream, timeout(1_000)).consume(any());
        facade.handleSpecialChatModes(request(3L, "会话二当前问题"), faultAgent(), "tenant-a");
        verify(secondStream, timeout(1_000)).consume(any());

        ArgumentCaptor<List<HermesChatClient.HermesMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(hermesChatClient, times(2)).open(messages.capture());
        assertEquals(List.of("会话一问题", "会话一回复", "会话一当前问题"),
            contents(messages.getAllValues().get(0)));
        assertEquals(List.of("会话二问题", "会话二回复", "会话二当前问题"),
            contents(messages.getAllValues().get(1)));
    }

    @Test
    void toolProgressIsForwardedButOnlyFinalAssistantTextIsPersisted() {
        ChatServiceFacade facade = facade();
        ChatRequest request = request(2L, "请诊断设备");
        when(hermesChatClient.modelName()).thenReturn("fault");
        when(chatMessageService.getMessagesBySessionIdAndUserId(2L, 1L, 20)).thenReturn(List.of());
        when(hermesChatClient.open(any())).thenReturn(hermesStream);
        when(hermesStream.consume(any())).thenAnswer(invocation -> {
            HermesChatClient.HermesStreamListener listener = invocation.getArgument(0);
            listener.onToolProgress(new HermesToolProgress("query_device_status"));
            listener.onContent("最终诊断回复");
            return new HermesChatResult("最终诊断回复");
        });

        facade.handleSpecialChatModes(request, faultAgent(), "tenant-a");

        ArgumentCaptor<String> contents = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> roles = ArgumentCaptor.forClass(String.class);
        verify(chatMessageService, timeout(1_000).times(2)).saveChatMessage(
            eq(1L), eq(2L), contents.capture(), roles.capture(), eq("fault"));
        assertEquals(List.of("请诊断设备", "最终诊断回复"), contents.getAllValues());
        assertEquals(List.of("user", "assistant"), roles.getAllValues());
    }

    @Test
    void hermesToolProgressUsesActualToolNameAndNeverLeaksRawProgressAsResult() {
        SseEventDto event = ChatServiceFacade.hermesToolProgressEvent(
            new HermesToolProgress("query_telemetry_statistics"));

        assertEquals("mcp_tool", event.getEvent());
        assertEquals("{\"toolName\":\"query_telemetry_statistics\",\"status\":\"pending\",\"result\":\"\"}",
            event.getContent());
        assertFalse(event.getContent().contains("running"));
        assertFalse(event.getContent().contains("{\\\"tool\\\""));
    }

    @Test
    void voiceFaultDiagnosisPublishesSessionSyncEventsWithoutChangingHermesFlow() {
        ChatServiceFacade facade = facade();
        ChatRequest request = request(2L, "请诊断设备");
        request.setSource("VOICE");
        request.setClientRequestId("voice-request-1");
        when(hermesChatClient.modelName()).thenReturn("fault");
        when(chatMessageService.getMessagesBySessionIdAndUserId(2L, 1L, 20)).thenReturn(List.of());
        when(hermesChatClient.open(any())).thenReturn(hermesStream);
        when(hermesStream.consume(any())).thenAnswer(invocation -> {
            HermesChatClient.HermesStreamListener listener = invocation.getArgument(0);
            listener.onToolProgress(new HermesToolProgress("query_device_status"));
            listener.onContent("诊断结果");
            return new HermesChatResult("诊断结果");
        });

        facade.handleSpecialChatModes(request, faultAgent(), "tenant-a");

        ArgumentCaptor<ChatSyncEvent> events = ArgumentCaptor.forClass(ChatSyncEvent.class);
        verify(chatSyncEventPublisher, timeout(1_000).times(4)).publish(events.capture());
        assertEquals(List.of(
            ChatSyncEventType.USER_MESSAGE,
            ChatSyncEventType.TOOL_PROGRESS,
            ChatSyncEventType.ASSISTANT_DELTA,
            ChatSyncEventType.ASSISTANT_DONE),
            events.getAllValues().stream().map(ChatSyncEvent::type).toList());
        assertEquals(Arrays.asList("请诊断设备", "query_device_status", "诊断结果", null),
            events.getAllValues().stream().map(ChatSyncEvent::content).toList());
        assertEquals("COMPLETED", events.getAllValues().get(3).status());
        assertEquals(List.of("voice-request-1", "voice-request-1", "voice-request-1", "voice-request-1"),
            events.getAllValues().stream().map(ChatSyncEvent::clientRequestId).toList());
        verify(hermesChatClient).open(any());
    }

    @Test
    void generalChatDoesNotInvokeFaultDiagnosisService() {
        ChatServiceFacade facade = facade();
        AgentVo generalAgent = faultAgent();
        generalAgent.setScenarioCode(AgentScenarioCode.GENERAL_CHAT.name());

        assertThrows(IllegalArgumentException.class,
            () -> facade.handleSpecialChatModes(request(), generalAgent, "tenant-a"));

        verifyNoInteractions(hermesChatClient);
    }

    @Test
    void workflowTakesPriorityOverFaultDiagnosisScenario() {
        ChatServiceFacade facade = facade();
        ChatRequest request = request();
        request.setEnableWorkFlow(true);
        WorkFlowRunner runner = new WorkFlowRunner();
        runner.setUuid("workflow-1");
        request.setWorkFlowRunner(runner);
        User user = new User();
        ThreadContext.setCurrentUser(user);
        SseEmitter workflowEmitter = new SseEmitter(1_000L);
        when(workFlowStarterService.streaming(user, "workflow-1", null, 2L)).thenReturn(workflowEmitter);

        assertSame(workflowEmitter, facade.handleSpecialChatModes(request, faultAgent(), "tenant-a"));

        verifyNoInteractions(hermesChatClient);
    }

    @Test
    void missingWorkflowRunnerSendsErrorAndReturnsWithoutNullPointerException() {
        ChatServiceFacade facade = facade();
        ChatRequest request = request();
        request.setEnableWorkFlow(true);

        try (MockedStatic<SseMessageUtils> sse = org.mockito.Mockito.mockStatic(SseMessageUtils.class)) {
            assertSame(request.getEmitter(), facade.handleSpecialChatModes(request, faultAgent(), "tenant-a"));

            sse.verify(() -> SseMessageUtils.sendError(1L, "工作流参数不能为空"));
            sse.verify(() -> SseMessageUtils.sendDone(1L));
            sse.verify(() -> SseMessageUtils.completeConnection(1L, "token-a"));
            verifyNoInteractions(workFlowStarterService);
        }
    }

    private ChatServiceFacade facade() {
        return new ChatServiceFacade(chatModelService, chatServiceFactory, knowledgeInfoService, vectorStoreService,
            knowledgeRetrievalService, sseEmitterManager, chatMessageService, workFlowStarterService,
            toolProviderFactory, agentService, langChain4jMcpToolProviderService, hermesChatClient,
            faultRequestPlanner, faultDiagnosisChatService, faultDiagnosisProperties, chatSyncEventPublisher);
    }

    private static ChatRequest request() {
        return request(2L, null);
    }

    private static ChatRequest request(Long sessionId, String content) {
        ChatRequest request = new ChatRequest();
        request.setUserId(1L);
        request.setSessionId(sessionId);
        request.setContent(content);
        request.setTokenValue("token-a");
        request.setEmitter(new SseEmitter(1_000L));
        return request;
    }

    private static List<String> contents(List<HermesChatClient.HermesMessage> messages) {
        return messages.stream().map(HermesChatClient.HermesMessage::content).toList();
    }

    private static HermesChatClient.HermesMessage last(List<HermesChatClient.HermesMessage> messages) {
        return messages.get(messages.size() - 1);
    }

    private static AgentVo faultAgent() {
        AgentVo agent = new AgentVo();
        agent.setId(7L);
        agent.setStatus("0");
        agent.setScenarioCode(AgentScenarioCode.FAULT_DIAGNOSIS.name());
        agent.setExecutionMode(AgentExecutionMode.DETERMINISTIC.name());
        return agent;
    }
}
