package org.ruoyi.service.chat.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.chat.base.ThreadContext;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.dto.request.WorkFlowRunner;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.chat.service.workFlow.IWorkFlowStarterService;
import org.ruoyi.common.sse.core.SseEmitterManager;
import org.ruoyi.common.sse.utils.SseMessageUtils;
import org.ruoyi.domain.enums.agent.AgentExecutionMode;
import org.ruoyi.domain.enums.agent.AgentScenarioCode;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.factory.ChatServiceFactory;
import org.ruoyi.mcp.service.core.LangChain4jMcpToolProviderService;
import org.ruoyi.mcp.service.core.ToolProviderFactory;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.fault.FaultDiagnosisChatService;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.service.vector.VectorStoreService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceFacadeRoutingTest {

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
    @Mock private FaultDiagnosisChatService faultDiagnosisChatService;

    @AfterEach
    void clearThreadContext() {
        ThreadContext.unload();
    }

    @Test
    void faultDiagnosisUsesDedicatedServiceAndSkipsGeneralKnowledgeRag() throws Exception {
        ChatServiceFacade facade = facade();
        ChatRequest request = request();
        AgentVo faultAgent = faultAgent();
        when(faultDiagnosisChatService.diagnose(request, faultAgent, 1L, "tenant-a")).thenReturn("确定性诊断结果");

        try (MockedStatic<SseMessageUtils> sse = org.mockito.Mockito.mockStatic(SseMessageUtils.class)) {
            CountDownLatch completed = new CountDownLatch(1);
            sse.when(() -> SseMessageUtils.completeConnection(1L, "token-a"))
                .thenAnswer(invocation -> {
                    completed.countDown();
                    return null;
                });
            assertSame(request.getEmitter(), facade.handleSpecialChatModes(request, faultAgent, "tenant-a"));

            verify(faultDiagnosisChatService, timeout(1_000)).diagnose(request, faultAgent, 1L, "tenant-a");
            verify(knowledgeInfoService, never()).queryById(any());
            verify(chatModelService, never()).selectModelByName(any());
            verify(chatMessageService, timeout(1_000)).saveChatMessage(1L, 2L, "确定性诊断结果", "assistant", null);
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            sse.verify(() -> SseMessageUtils.completeConnection(1L, "token-a"));
        }
    }

    @Test
    void generalChatDoesNotInvokeFaultDiagnosisService() {
        ChatServiceFacade facade = facade();
        AgentVo generalAgent = faultAgent();
        generalAgent.setScenarioCode(AgentScenarioCode.GENERAL_CHAT.name());

        assertThrows(IllegalArgumentException.class,
            () -> facade.handleSpecialChatModes(request(), generalAgent, "tenant-a"));

        verifyNoInteractions(faultDiagnosisChatService);
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

        verifyNoInteractions(faultDiagnosisChatService);
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
            toolProviderFactory, agentService, langChain4jMcpToolProviderService, faultDiagnosisChatService);
    }

    private static ChatRequest request() {
        ChatRequest request = new ChatRequest();
        request.setUserId(1L);
        request.setSessionId(2L);
        request.setTokenValue("token-a");
        request.setEmitter(new SseEmitter(1_000L));
        return request;
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
