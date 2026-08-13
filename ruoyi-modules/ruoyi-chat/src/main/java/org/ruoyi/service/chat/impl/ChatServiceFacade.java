package org.ruoyi.service.chat.impl;

import cn.dev33.satoken.stp.StpUtil;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.supervisor.SupervisorAgent;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.skills.FileSystemSkill;
import dev.langchain4j.skills.FileSystemSkillLoader;
import dev.langchain4j.skills.shell.ShellSkills;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.agent.ChartGenerationAgent;
import org.ruoyi.agent.ChitChatAgent;
import org.ruoyi.agent.EchartsAgent;
import org.ruoyi.agent.SkillsAgent;
import org.ruoyi.agent.SqlAgent;
import org.ruoyi.agent.WebSearchAgent;
import org.ruoyi.agent.tool.ExecuteSqlQueryTool;
import org.ruoyi.agent.tool.QueryAllTablesTool;
import org.ruoyi.agent.tool.QueryTableSchemaTool;
import org.ruoyi.common.chat.base.ThreadContext;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.dto.request.WorkFlowRunner;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.enums.RoleType;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.chat.service.chat.IChatService;
import org.ruoyi.common.chat.service.workFlow.IWorkFlowStarterService;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.ObjectUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.sse.core.SseEmitterManager;
import org.ruoyi.common.sse.utils.SseMessageUtils;
import org.ruoyi.config.agent.SkillsPathResolver;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.enums.agent.AgentScenarioCode;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.factory.ChatServiceFactory;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.mcp.service.core.LangChain4jMcpToolProviderService;
import org.ruoyi.mcp.service.core.ToolProviderFactory;
import org.ruoyi.observability.*;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.chat.AbstractChatService;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.chat.impl.memory.PersistentChatMemoryStore;
import org.ruoyi.service.chat.hermes.HermesChatClient;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesChatCancelledException;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesChatException;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesMessage;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesStream;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesToolProgress;
import org.ruoyi.service.fault.FaultDiagnosisChatService;
import org.ruoyi.service.fault.FaultRequestPlanner;
import org.ruoyi.service.fault.model.FaultReportChatResult;
import org.ruoyi.service.fault.model.FaultRequestPlan;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.service.knowledge.retriever.CustomVectorRetriever;
import org.ruoyi.service.vector.VectorStoreService;
import org.ruoyi.websocket.chat.sync.ChatSyncEvent;
import org.ruoyi.websocket.chat.sync.ChatSyncEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天服务门面层
 * <p>
 * 作为统一入口，负责：
 * 1. 构建对话上下文
 * 2. 路由到对应的处理器
 *
 * @author ageerle@163.com
 * @date 2025/12/13
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceFacade implements IChatService {

    private static final Integer DEFAULT_MAX_MESSAGES = 20;

    private final IChatModelService chatModelService;

    private final ChatServiceFactory chatServiceFactory;

    private final IKnowledgeInfoService knowledgeInfoService;

    private final VectorStoreService vectorStoreService;

    private final KnowledgeRetrievalService knowledgeRetrievalService;

    private final SseEmitterManager sseEmitterManager;

    private final IChatMessageService chatMessageService;

    private final IWorkFlowStarterService workFlowStarterService;

    private final ToolProviderFactory toolProviderFactory;

    private final IAgentService agentService;

    private final LangChain4jMcpToolProviderService langChain4jMcpToolProviderService;

    private final HermesChatClient hermesChatClient;

    private final FaultRequestPlanner faultRequestPlanner;

    private final FaultDiagnosisChatService faultDiagnosisChatService;

    private final FaultDiagnosisProperties faultDiagnosisProperties;

    /** 语音请求到 Web Chat 的旁路同步；不参与模型调用或 SSE 生命周期。 */
    private final ChatSyncEventPublisher chatSyncEventPublisher;

    /**
     * 内存实例缓存，避免同一会话重复创建
     * Key: sessionId, Value: MessageWindowChatMemory实例
     */
    private static final Map<Object, MessageWindowChatMemory> memoryCache = new ConcurrentHashMap<>();



    /**
     * 统一聊天入口 - SSE流式响应
     *
     * @param chatRequest 聊天请求
     * @return SseEmitter
     */
    public SseEmitter sseChat(ChatRequest chatRequest) {

        // 4. 具体的服务实现
        Long userId = LoginHelper.getUserId();
        String tokenValue = StpUtil.getTokenValue();
        String tenantId = LoginHelper.getTenantId();
        SseEmitter emitter = sseEmitterManager.connect(userId, tokenValue);

        chatRequest.setEmitter(emitter);
        chatRequest.setUserId(userId);
        chatRequest.setTokenValue(tokenValue);
        try {
            // 路由需要先解析 Agent；模型、上下文和 RAG 只在通用 Agent 路径中解析。
            AgentVo agentVo = chatRequest.getAgentId() == null ? null : agentService.queryById(chatRequest.getAgentId());
            return handleSpecialChatModes(chatRequest, agentVo, tenantId);
        } catch (ServiceException e) {
            SseMessageUtils.sendError(userId, e.getMessage());
        } catch (Exception e) {
            log.error("聊天同步准备失败", e);
            SseMessageUtils.sendError(userId, "聊天准备失败，请稍后重试");
        }
        SseMessageUtils.sendDone(userId);
        SseMessageUtils.completeConnection(userId, tokenValue);
        return emitter;
    }

    /**
     * 路由对话模式：仅两种情况——工作流对话 / 智能体对话。
     *
     * @param chatRequest 聊天请求
     * @param agentVo    智能体配置（可为 null）
     * @return 对应模式的 SseEmitter
     */
    SseEmitter handleSpecialChatModes(ChatRequest chatRequest, AgentVo agentVo, String tenantId) {
        // 模式1：工作流对话（前端应用市场选工作流后携带 workFlowRunner）
        if (Boolean.TRUE.equals(chatRequest.getEnableWorkFlow())) {
            log.info("处理工作流对话,会话: {}", chatRequest.getSessionId());
            saveUserMessage(chatRequest);
            WorkFlowRunner runner = chatRequest.getWorkFlowRunner();
            if (ObjectUtils.isEmpty(runner)) {
                log.warn("工作流参数为空");
                SseMessageUtils.sendError(chatRequest.getUserId(), "工作流参数不能为空");
                SseMessageUtils.sendDone(chatRequest.getUserId());
                SseMessageUtils.completeConnection(chatRequest.getUserId(), chatRequest.getTokenValue());
                return chatRequest.getEmitter();
            }
            return workFlowStarterService.streaming(
                ThreadContext.getCurrentUser(),
                runner.getUuid(),
                runner.getInputs(),
                chatRequest.getSessionId()
            );
        }
        // 明确报告请求由本地确定性链路处理，其余 FAULT_DIAGNOSIS 交给 Hermes；都不回退通用 Supervisor。
        if (isFaultDiagnosisAgent(agentVo)) {
            chatRequest.setModel(hermesChatClient.modelName());
            java.util.Optional<FaultRequestPlan> reportPlan = faultRequestPlanner.planExplicitReportRequest(
                chatRequest.getContent(), faultDiagnosisProperties.getAllowedAssets());
            if (reportPlan.isPresent()) {
                saveUserMessage(chatRequest);
                return handleDeterministicFaultReportChat(chatRequest, agentVo, tenantId, reportPlan.get());
            }
            // 历史必须在保存本轮用户消息前读取，且当前用户消息始终为 Hermes 请求的最后一条。
            List<HermesMessage> messages = buildHermesMessages(chatRequest, agentVo);
            saveUserMessage(chatRequest);
            return handleHermesFaultChat(chatRequest, messages);
        }
        // 模式2：智能体对话（默认走 Supervisor 多 Agent 编排）
        prepareGeneralAgentChat(chatRequest, agentVo);
        saveUserMessage(chatRequest);
        return handleAgentChat(chatRequest, agentVo);
    }

    private void saveUserMessage(ChatRequest chatRequest) {
        chatMessageService.saveChatMessage(chatRequest.getUserId(), chatRequest.getSessionId(), chatRequest.getContent(),
            RoleType.USER.getName(), chatRequest.getModel());
        publishVoiceSync(chatRequest, ChatSyncEvent.userMessage(
            chatRequest.getSessionId(), chatRequest.getClientRequestId(), chatRequest.getContent()));
    }

    /** Only fault-diagnosis agents use Hermes' fault model and ruoyi_fault tool. */
    private boolean isFaultDiagnosisAgent(AgentVo agentVo) {
        return agentVo != null && AgentScenarioCode.FAULT_DIAGNOSIS.name().equals(agentVo.getScenarioCode());
    }

    /** 通用 Agent 路径才需要模型解析、上下文和知识库 RAG。 */
    private void prepareGeneralAgentChat(ChatRequest chatRequest, AgentVo agentVo) {
        if (agentVo != null && agentVo.getModelId() != null) {
            ChatModelVo agentModel = chatModelService.queryById(agentVo.getModelId());
            if (agentModel != null) {
                chatRequest.setModel(agentModel.getModelName());
            }
        } else if (chatRequest.getAgentId() != null) {
            log.warn("智能体不存在或未配置模型，回退到 model 字段: agentId={}", chatRequest.getAgentId());
        }
        ChatModelVo chatModelVo = chatModelService.selectModelByName(chatRequest.getModel());
        if (chatModelVo == null) {
            throw new IllegalArgumentException("模型不存在: " + chatRequest.getModel());
        }
        chatRequest.setChatModelVo(chatModelVo);
        chatRequest.setContextMessages(buildContextMessages(chatRequest, agentVo));
    }

    /**
     * Hermes owns Agent inference and the ruoyi_fault tool; RuoYi only owns history,
     * persistence and its established frontend SSE protocol.
     */
    private SseEmitter handleHermesFaultChat(ChatRequest chatRequest, List<HermesMessage> messages) {
        Long userId = chatRequest.getUserId();
        String tokenValue = chatRequest.getTokenValue();
        HermesStream stream;
        try {
            stream = hermesChatClient.open(messages);
        } catch (HermesChatException e) {
            SseMessageUtils.sendError(userId, e.getMessage());
            SseMessageUtils.sendDone(userId);
            publishVoiceSync(chatRequest, ChatSyncEvent.assistantDone(
                chatRequest.getSessionId(), chatRequest.getClientRequestId(), "ERROR"));
            SseMessageUtils.completeConnection(userId, tokenValue);
            return chatRequest.getEmitter();
        }
        java.util.concurrent.atomic.AtomicBoolean completedNormally = new java.util.concurrent.atomic.AtomicBoolean();
        // Servlet completion/timeout/error means the browser is gone: immediately cancel Hermes' upstream call.
        chatRequest.getEmitter().onCompletion(() -> cancelHermesStream(stream, completedNormally));
        chatRequest.getEmitter().onTimeout(() -> cancelHermesStream(stream, completedNormally));
        chatRequest.getEmitter().onError(ignored -> cancelHermesStream(stream, completedNormally));
        CompletableFuture.runAsync(() -> {
            try {
                String result = stream.consume(new HermesChatClient.HermesStreamListener() {
                    @Override
                    public void onContent(String content) {
                        SseMessageUtils.sendContent(userId, content);
                        publishVoiceSync(chatRequest, ChatSyncEvent.assistantDelta(
                            chatRequest.getSessionId(), chatRequest.getClientRequestId(), content));
                    }

                    @Override
                    public void onToolProgress(HermesToolProgress progress) {
                        // Keep the existing mcp_tool SSE contract, but expose only the real tool name and its start state.
                        SseMessageUtils.sendEvent(userId, hermesToolProgressEvent(progress));
                        publishVoiceSync(chatRequest, ChatSyncEvent.toolProgress(
                            chatRequest.getSessionId(), chatRequest.getClientRequestId(), progress.toolName()));
                    }
                }).content();
                completedNormally.set(true);
                if (StringUtils.isNotBlank(result)) {
                    chatMessageService.saveChatMessage(userId, chatRequest.getSessionId(), result,
                        RoleType.ASSISTANT.getName(), chatRequest.getModel());
                }
                SseMessageUtils.sendDone(userId);
                publishVoiceSync(chatRequest, ChatSyncEvent.assistantDone(
                    chatRequest.getSessionId(), chatRequest.getClientRequestId(), "COMPLETED"));
            } catch (HermesChatCancelledException ignored) {
                // The downstream SSE connection has gone away, so there is no response to forward or persist.
                publishVoiceSync(chatRequest, ChatSyncEvent.assistantDone(
                    chatRequest.getSessionId(), chatRequest.getClientRequestId(), "CANCELLED"));
            } catch (ServiceException e) {
                SseMessageUtils.sendError(userId, e.getMessage());
                SseMessageUtils.sendDone(userId);
                publishVoiceSync(chatRequest, ChatSyncEvent.assistantDone(
                    chatRequest.getSessionId(), chatRequest.getClientRequestId(), "ERROR"));
            } catch (Exception e) {
                log.error("Hermes 故障诊断执行失败", e);
                SseMessageUtils.sendError(userId, "Hermes 服务调用失败，请稍后重试");
                SseMessageUtils.sendDone(userId);
                publishVoiceSync(chatRequest, ChatSyncEvent.assistantDone(
                    chatRequest.getSessionId(), chatRequest.getClientRequestId(), "ERROR"));
            } finally {
                SseMessageUtils.completeConnection(userId, tokenValue);
            }
        });
        return chatRequest.getEmitter();
    }

    /** 明确的报告请求在 RuoYi 内完成生成与持久化，并通过专用 SSE 事件附加报告卡片。 */
    private SseEmitter handleDeterministicFaultReportChat(ChatRequest chatRequest, AgentVo agent,
                                                          String tenantId, FaultRequestPlan reportPlan) {
        Long userId = chatRequest.getUserId();
        String tokenValue = chatRequest.getTokenValue();
        CompletableFuture.runAsync(() -> {
            try {
                FaultReportChatResult result = faultDiagnosisChatService.generateReport(
                    chatRequest, agent, reportPlan, userId, tenantId);
                if (StringUtils.isNotBlank(result.content())) {
                    SseMessageUtils.sendContent(userId, result.content());
                    publishVoiceSync(chatRequest, ChatSyncEvent.assistantDelta(
                        chatRequest.getSessionId(), chatRequest.getClientRequestId(), result.content()));
                    chatMessageService.saveChatMessage(userId, chatRequest.getSessionId(), result.content(),
                        RoleType.ASSISTANT.getName(), chatRequest.getModel());
                }
                if (result.attachment() != null) {
                    SseMessageUtils.sendEvent(userId,
                        org.ruoyi.common.sse.dto.SseEventDto.report(result.attachment().toEventData()));
                }
                SseMessageUtils.sendDone(userId);
                publishVoiceSync(chatRequest, ChatSyncEvent.assistantDone(
                    chatRequest.getSessionId(), chatRequest.getClientRequestId(), "COMPLETED"));
            } catch (ServiceException e) {
                SseMessageUtils.sendError(userId, e.getMessage());
                SseMessageUtils.sendDone(userId);
                publishVoiceSync(chatRequest, ChatSyncEvent.assistantDone(
                    chatRequest.getSessionId(), chatRequest.getClientRequestId(), "ERROR"));
            } catch (Exception e) {
                log.error("确定性运行报告生成失败", e);
                SseMessageUtils.sendError(userId, "运行报告生成失败，请稍后重试");
                SseMessageUtils.sendDone(userId);
                publishVoiceSync(chatRequest, ChatSyncEvent.assistantDone(
                    chatRequest.getSessionId(), chatRequest.getClientRequestId(), "ERROR"));
            } finally {
                SseMessageUtils.completeConnection(userId, tokenValue);
            }
        });
        return chatRequest.getEmitter();
    }

    static org.ruoyi.common.sse.dto.SseEventDto hermesToolProgressEvent(HermesToolProgress progress) {
        String toolName = progress == null ? "Hermes 工具" : progress.toolName();
        return org.ruoyi.common.sse.dto.SseEventDto.mcpTool(toolName, "pending", null);
    }

    private static void cancelHermesStream(HermesStream stream, java.util.concurrent.atomic.AtomicBoolean completedNormally) {
        if (!completedNormally.get()) {
            stream.cancel();
        }
    }

    private void publishVoiceSync(ChatRequest chatRequest, ChatSyncEvent event) {
        if ("VOICE".equalsIgnoreCase(chatRequest.getSource())) {
            chatSyncEventPublisher.publish(event);
        }
    }

    /** Build a bounded OpenAI-compatible transcript while retaining RuoYi as the history authority. */
    private List<HermesMessage> buildHermesMessages(ChatRequest chatRequest, AgentVo agentVo) {
        List<HermesMessage> messages = new ArrayList<>();
        if (agentVo != null && StringUtils.isNotBlank(agentVo.getSystemPrompt())) {
            messages.add(new HermesMessage(RoleType.SYSTEM.getName(), agentVo.getSystemPrompt()));
        }
        List<ChatMessage> history = chatMessageService.getMessagesBySessionIdAndUserId(
            chatRequest.getSessionId(), chatRequest.getUserId(), DEFAULT_MAX_MESSAGES);
        if (history != null) {
            for (ChatMessage message : history) {
                if (message instanceof UserMessage userMessage) {
                    messages.add(new HermesMessage(RoleType.USER.getName(), userMessage.singleText()));
                } else if (message instanceof AiMessage aiMessage) {
                    messages.add(new HermesMessage(RoleType.ASSISTANT.getName(), aiMessage.text()));
                }
            }
        }
        messages.add(new HermesMessage(RoleType.USER.getName(), chatRequest.getContent()));
        return messages;
    }

    /**
     * 智能体对话模式（默认）：构建 Supervisor 多 Agent 编排并异步执行，结果通过 SSE 推送。
     *
     * @param chatRequest 聊天请求
     * @param agentVo    智能体配置（可为 null，无智能体时用请求 model 兜底）
     */
    private SseEmitter handleAgentChat(ChatRequest chatRequest, AgentVo agentVo) {
        ChatModelVo chatModelVo = chatRequest.getChatModelVo();

        // 配置监督者模型：统一按 providerCode 走对应 AbstractChatService.buildChatModel，
        // 兼容 ZhiPu/QianWen/Ollama/Dify/Coze/CustomApi 等非 OpenAI 协议；默认实现为 OpenAI 兼容。
        AbstractChatService chatService = chatServiceFactory.getOriginalService(chatModelVo.getProviderCode());
        ChatModel plannerModel = chatService.buildChatModel(chatModelVo);

        Long userId = chatRequest.getUserId();

        // 工具装配：仅加载智能体显式关联的 MCP 工具。
        // 不能在未配置工具时回退到旧的硬编码 Stdio MCP 客户端：该客户端使用了
        // Windows npx.cmd 路径，而服务端 Docker 容器运行在 Linux，导致普通聊天在
        // 模型调用前就失败。
        ToolProvider toolProvider = null;
        if (agentVo != null && agentVo.getMcpToolIds() != null && !agentVo.getMcpToolIds().isEmpty()) {
            toolProvider = langChain4jMcpToolProviderService.getToolProvider(agentVo.getMcpToolIds());
        }

        // Skills 装配：智能体有勾选技能名时按名过滤磁盘 skills，否则加载全部
        ShellSkills skills = buildShellSkills(agentVo);

        // 构建子 Agent
        var searchAgentBuilder = AgenticServices.agentBuilder(WebSearchAgent.class)
            .chatModel(plannerModel)
            .listener(new MyAgentListener());
        if (toolProvider != null) {
            searchAgentBuilder.toolProvider(toolProvider);
        }
        WebSearchAgent searchAgent = searchAgentBuilder.build();

        // SkillsAgent：仅当有可用 skills 时才注入 systemMessage + toolProvider
        var skillsAgentBuilder = AgenticServices.agentBuilder(SkillsAgent.class)
            .chatModel(plannerModel);
        if (skills != null) {
            skillsAgentBuilder
                .systemMessage("You have access to the following skills:\n" + skills.formatAvailableSkills()
                    + "\nWhen the user's request relates to one of these skills, activate it first using the `activate_skill` tool before proceeding.")
                .toolProvider(skills.toolProvider());
        }
        SkillsAgent skillsAgent = skillsAgentBuilder.build();

        // 构建子 Agent 3: SqlAgent - 负责数据库查询
        SqlAgent sqlAgent = AgenticServices.agentBuilder(SqlAgent.class)
            .chatModel(plannerModel)
            .tools(new QueryAllTablesTool(), new QueryTableSchemaTool(), new ExecuteSqlQueryTool())
            .listener(new MyAgentListener())
            .build();

        // 构建子 Agent 4: ChartGenerationAgent - 负责图表生成
        ChartGenerationAgent chartGenerationAgent = AgenticServices.agentBuilder(ChartGenerationAgent.class)
            .chatModel(plannerModel)
            .listener(new MyAgentListener())
            .build();

        // 构建子 Agent 5: EchartsAgent - 负责数据可视化（结合 SQL 查询生成 Echarts 图表）
        EchartsAgent echartsAgent = AgenticServices.agentBuilder(EchartsAgent.class)
            .chatModel(plannerModel)
            .tools(new QueryAllTablesTool(), new QueryTableSchemaTool(), new ExecuteSqlQueryTool())
            .listener(new MyAgentListener())
            .build();

        // 构建子 Agent 6: ChitChatAgent - 简单闲聊兜底,避免无子 Agent 可用时 supervisor 空转
        ChitChatAgent chitChatAgent = AgenticServices.agentBuilder(ChitChatAgent.class)
            .chatModel(plannerModel)
            .build();

        // 构建监督者 Agent - 管理多个子 Agent
        var supervisorBuilder = AgenticServices.supervisorBuilder()
            .chatModel(plannerModel)
            .subAgents(skillsAgent, searchAgent, sqlAgent, chartGenerationAgent, echartsAgent, chitChatAgent)
            .supervisorContext("仅当请求是问候或简单闲聊、不需要任何数据、搜索、技能或图表时,才使用 chitChatAgent;"
                + "其余情况必须使用对应的专业 Agent")
            .responseStrategy(SupervisorResponseStrategy.SUMMARY);
        SupervisorAgent supervisor = supervisorBuilder.build();

        // 知识库增强：智能体绑定了知识库时，对 supervisor 输入做一次 RAG 增强
        String augmentedInput = augmentAgentInput(chatRequest, agentVo);
        // 智能体自定义系统提示词：supervisor builder 不支持 systemMessage，前置到输入
        String prompt = (agentVo != null && StringUtils.isNotBlank(agentVo.getSystemPrompt()))
            ? agentVo.getSystemPrompt() + "\n\n" + augmentedInput
            : augmentedInput;

        String tokenValue = chatRequest.getTokenValue();

        // 异步执行 supervisor，避免阻塞 HTTP 请求线程导致 SSE 事件被缓冲
        CompletableFuture.runAsync(() -> {
            try {
                String result = supervisor.invoke(prompt);
                SseMessageUtils.sendContent(userId, result);
                SseMessageUtils.sendDone(userId);
                // 保存助手回复到数据库（智能体对话为默认路径后，需在此落库以保留历史）
                if (StringUtils.isNotBlank(result)) {
                    chatMessageService.saveChatMessage(userId, chatRequest.getSessionId(),
                        result, RoleType.ASSISTANT.getName(), chatRequest.getModel());
                }
            } catch (Exception e) {
                log.error("Supervisor 执行失败", e);
                SseMessageUtils.sendError(userId, e.getMessage());
            } finally {
                SseMessageUtils.completeConnection(userId, tokenValue);
            }
        });
        return chatRequest.getEmitter();
    }

    /**
     * 兜底 MCP 工具装配（无智能体时使用，保留原有 3 个硬编码客户端逻辑）
     */
    private ToolProvider buildDefaultMcpToolProvider(Long userId) {
        McpTransport playwrightTransport = new StdioMcpTransport.Builder()
            .command(List.of("C:\\Program Files\\nodejs\\npx.cmd", "-y", "@playwright/mcp@latest"))
            .logEvents(true)
            .build();
        McpClient playwrightMcpClient = new DefaultMcpClient.Builder()
            .transport(playwrightTransport)
            .listener(new MyMcpClientListener(userId))
            .build();

        String userDir = System.getProperty("user.dir");
        McpTransport filesystemTransport = new StdioMcpTransport.Builder()
            .command(List.of("C:\\Program Files\\nodejs\\npx.cmd", "-y",
                "@modelcontextprotocol/server-filesystem", userDir))
            .logEvents(true)
            .build();
        McpClient filesystemMcpClient = new DefaultMcpClient.Builder()
            .transport(filesystemTransport)
            .listener(new MyMcpClientListener(userId))
            .build();

        return McpToolProvider.builder()
            .mcpClients(List.of(playwrightMcpClient, filesystemMcpClient))
            .build();
    }

    /**
     * 装配磁盘 ShellSkills：智能体勾选了技能名时按名过滤，否则加载全部。
     * 无 skills 时返回 null（调用方据此跳过 SkillsAgent 的 toolProvider 注入）
     */
    private ShellSkills buildShellSkills(AgentVo agentVo) {
        java.nio.file.Path skillsPath = SkillsPathResolver.resolveSkillsPath();
        List<FileSystemSkill> skillsList = FileSystemSkillLoader.loadSkills(skillsPath);
        if (skillsList == null || skillsList.isEmpty()) {
            return null;
        }
        if (agentVo != null && agentVo.getSkillNames() != null && !agentVo.getSkillNames().isEmpty()) {
            skillsList = skillsList.stream()
                .filter(s -> agentVo.getSkillNames().contains(s.name()))
                .toList();
            if (skillsList.isEmpty()) {
                return null;
            }
        }
        return ShellSkills.from(skillsList);
    }

    /**
     * 智能体对话下的输入增强：智能体绑定知识库时，对原始 content 做多知识库 RAG 增强。
     * 无知识库时原样返回 content。
     */
    private String augmentAgentInput(ChatRequest chatRequest, AgentVo agentVo) {
        String content = chatRequest.getContent();
        List<Long> knowledgeIds = collectKnowledgeIds(chatRequest, agentVo);
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return content;
        }
        try {
            RetrievalAugmentor augmentor = buildMultiKnowledgeAugmentor(knowledgeIds);
            if (augmentor == null) {
                return content;
            }
            UserMessage userMessage = UserMessage.userMessage(content);
            Metadata metadata = Metadata.from(userMessage, chatRequest.getSessionId(), new ArrayList<>());
            AugmentationResult result = augmentor.augment(new AugmentationRequest(userMessage, metadata));
            ChatMessage augmented = result.chatMessage();
            return augmented instanceof UserMessage ? ((UserMessage) augmented).singleText() : content;
        } catch (Exception e) {
            log.warn("智能体对话 RAG 增强失败，回退原始输入: {}", e.getMessage());
            return content;
        }
    }

    /**
     * 支持外部 handler 的对话接口（跨模块调用）
     * 同时发送到 SSE 和外部 handler
     *
     * @param chatRequest     聊天请求
     * @param externalHandler 外部响应处理器（可为 null）
     */
    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler externalHandler) {
        if (chatRequest.getAgentId() != null) {
            AgentVo agent = agentService.queryById(chatRequest.getAgentId());
            if (isFaultDiagnosisAgent(agent)) {
                throw new ServiceException("故障诊断Agent不支持直接流式模型入口，请使用统一聊天入口");
            }
        }
        // 1. 根据模型名称查询完整配置
        ChatModelVo chatModelVo = chatModelService.selectModelByName(chatRequest.getModel());
        if (chatModelVo == null) {
            throw new IllegalArgumentException("模型不存在: " + chatRequest.getModel());
        }

        // 3. 路由服务提供商
        String providerCode = chatModelVo.getProviderCode();
        log.info("跨模块调用 - 路由到服务提供商: {}, 模型: {}", providerCode, chatRequest.getModel());
        AbstractChatService chatService = chatServiceFactory.getOriginalService(providerCode);

        // 4. 获取用户信息
        Long userId = LoginHelper.getUserId();
        String tokenValue = StpUtil.getTokenValue();

        // 5. 建立 SSE 连接（用于前端监听）
        sseEmitterManager.connect(userId, tokenValue);

        // 保存用户消息
        chatMessageService.saveChatMessage(userId, chatRequest.getSessionId(), chatRequest.getContent(), RoleType.USER.getName(), chatRequest.getModel());

        // 6. 创建组合 handler：同时发送到 SSE 和外部 handler
        StreamingChatResponseHandler combinedHandler = createCombinedHandler(userId, tokenValue, externalHandler);

        // 7. 发起对话
        StreamingChatModel streamingChatModel = chatService.buildStreamingChatModel(chatModelVo, chatRequest);
        streamingChatModel.chat(chatRequest.getContent(), combinedHandler);
    }

    /**
     * 实现接口默认方法 - 不带 handler 的调用
     */
    @Override
    public SseEmitter chat(ChatRequest chatRequest) {
        return sseChat(chatRequest);
    }


    /**
     * 创建或获取聊天内存实例（缓存机制）
     * 同一个会话ID会返回同一个内存实例，避免重复创建和消息丢失
     *
     * @param memoryId 内存ID（会话ID）
     * @return MessageWindowChatMemory实例
     */
    private MessageWindowChatMemory createChatMemory(Object memoryId) {
        // 先从缓存中获取
        return memoryCache.computeIfAbsent(memoryId, key -> {
            try {
                PersistentChatMemoryStore store = new PersistentChatMemoryStore(chatMessageService);
                return MessageWindowChatMemory.builder()
                    .id(memoryId)
                    .maxMessages(DEFAULT_MAX_MESSAGES)
                    .chatMemoryStore(store)
                    .build();
            } catch (Exception e) {
                log.warn("创建聊天内存失败: {}", e.getMessage());
                return null;
            }
        });
    }


    /**
     * 构建上下文消息列表
     * 消息顺序：系统提示词 → 历史消息 → 当前用户消息（确保 AI 正确理解对话上下文）
     *
     * @param chatRequest 聊天请求
     * @param agentVo     智能体配置（可为 null）
     * @return 上下文消息列表
     */
    private List<ChatMessage> buildContextMessages(ChatRequest chatRequest, AgentVo agentVo) {
        List<ChatMessage> messages = new ArrayList<>();

        // 0. 智能体自定义系统提示词（普通对话今天无 SystemMessage，这里新增注入点）
        if (agentVo != null && StringUtils.isNotBlank(agentVo.getSystemPrompt())) {
            messages.add(SystemMessage.from(agentVo.getSystemPrompt()));
        }

        // 1. 初始化当前用户消息
        UserMessage userMessage = UserMessage.userMessage(chatRequest.getContent());

        // 2. 知识库检索增强 (RAG)：智能体的 knowledgeIds 优先，回退到请求的 knowledgeId
        List<Long> knowledgeIds = collectKnowledgeIds(chatRequest, agentVo);
        if (knowledgeIds != null && !knowledgeIds.isEmpty()) {
            RetrievalAugmentor augmentor = buildMultiKnowledgeAugmentor(knowledgeIds);
            if (augmentor != null) {
                log.info("执行多知识库 RAG 流程: kids={}", knowledgeIds);
                Metadata metadata = Metadata.from(userMessage, chatRequest.getSessionId(), new ArrayList<>());
                AugmentationRequest augmentationRequest = new AugmentationRequest(userMessage, metadata);
                AugmentationResult result = augmentor.augment(augmentationRequest);
                ChatMessage augmented = result.chatMessage();
                if (augmented instanceof UserMessage) {
                    userMessage = (UserMessage) augmented;
                    log.debug("RAG 增强完成，UserMessage 已注入背景知识");
                }
            }
        }

        // 3. 从数据库查询历史对话消息（放在前面）
        if (chatRequest.getSessionId() != null) {
            MessageWindowChatMemory memory = createChatMemory(chatRequest.getSessionId());
            if (memory != null) {
                List<ChatMessage> historicalMessages = memory.messages();
                if (historicalMessages != null && !historicalMessages.isEmpty()) {
                    messages.addAll(historicalMessages);
                    log.debug("已加载 {} 条历史消息用于会话 {}", historicalMessages.size(), chatRequest.getSessionId());
                }
            }
        }

        // 4. 添加经过增强的用户消息（放在最后）
        messages.add(userMessage);

        return messages;
    }

    /**
     * 汇总本次对话要检索的知识库ID列表：智能体绑定的 knowledgeIds 优先，回退到请求的 knowledgeId
     */
    private List<Long> collectKnowledgeIds(ChatRequest chatRequest, AgentVo agentVo) {
        if (agentVo != null && agentVo.getKnowledgeIds() != null && !agentVo.getKnowledgeIds().isEmpty()) {
            return agentVo.getKnowledgeIds();
        }
        if (StringUtils.isNotBlank(chatRequest.getKnowledgeId())) {
            try {
                return List.of(Long.valueOf(chatRequest.getKnowledgeId()));
            } catch (NumberFormatException ignored) {
            }
        }
        return List.of();
    }

    /**
     * 构建多知识库复合检索增强器。
     * 单知识库直接用 DefaultRetrievalAugmentor + CustomVectorRetriever；
     * 多知识库用一个复合 ContentRetriever 合并各库检索结果。
     */
    private RetrievalAugmentor buildMultiKnowledgeAugmentor(List<Long> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return null;
        }
        List<ContentRetriever> retrievers = new ArrayList<>();
        for (Long kid : knowledgeIds) {
            try {
                KnowledgeInfoVo kb = knowledgeInfoService.queryById(kid);
                if (kb == null) {
                    continue;
                }
                ChatModelVo embModel = chatModelService.selectModelByName(kb.getEmbeddingModel());
                if (embModel == null) {
                    log.warn("知识库向量模型未配置或不存在: kid={}, embeddingModel={}", kid, kb.getEmbeddingModel());
                    continue;
                }
                retrievers.add(new CustomVectorRetriever(knowledgeRetrievalService, kb, embModel));
            } catch (Exception e) {
                log.warn("构建知识库检索器失败: kid={}, err={}", kid, e.getMessage());
            }
        }
        if (retrievers.isEmpty()) {
            return null;
        }
        // 单库直接返回；多库用复合检索器
        ContentRetriever composite = retrievers.size() == 1
            ? retrievers.get(0)
            : new CompositeContentRetriever(retrievers);
        return DefaultRetrievalAugmentor.builder()
            .contentRetriever(composite)
            .build();
    }

    /**
     * 复合内容检索器：对多个知识库检索器并发查询并合并结果
     */
    private static class CompositeContentRetriever implements ContentRetriever {
        private final List<ContentRetriever> delegates;

        CompositeContentRetriever(List<ContentRetriever> delegates) {
            this.delegates = delegates;
        }

        @Override
        public List<Content> retrieve(Query query) {
            List<Content> all = new ArrayList<>();
            for (ContentRetriever r : delegates) {
                try {
                    List<Content> part = r.retrieve(query);
                    if (part != null) {
                        all.addAll(part);
                    }
                } catch (Exception e) {
                    log.warn("复合检索子检索器异常: {}", e.getMessage());
                }
            }
            return all;
        }
    }

    /**
     * 构建向量查询参数
     */
    private QueryVectorBo buildQueryVectorBo(ChatRequest chatRequest, KnowledgeInfoVo knowledgeInfoVo,
                                             ChatModelVo chatModel) {
        QueryVectorBo queryVectorBo = new QueryVectorBo();
        queryVectorBo.setQuery(chatRequest.getContent());
        queryVectorBo.setKid(chatRequest.getKnowledgeId());
        queryVectorBo.setApiKey(chatModel.getApiKey());
        queryVectorBo.setBaseUrl(chatModel.getApiHost());
        queryVectorBo.setVectorModelName(knowledgeInfoVo.getVectorModel());
        queryVectorBo.setEmbeddingModelName(knowledgeInfoVo.getEmbeddingModel());
        queryVectorBo.setMaxResults(knowledgeInfoVo.getRetrieveLimit());

        // 设置重排序参数
        queryVectorBo.setEnableRerank(knowledgeInfoVo.getEnableRerank() != null && knowledgeInfoVo.getEnableRerank() == 1);
        queryVectorBo.setRerankModelName(knowledgeInfoVo.getRerankModel());
        queryVectorBo.setRerankTopN(knowledgeInfoVo.getRerankTopN());
        queryVectorBo.setRerankScoreThreshold(knowledgeInfoVo.getRerankScoreThreshold());

        return queryVectorBo;
    }

    /**
     * 创建组合响应处理器 - 同时发送到 SSE 和外部 handler
     *
     * @param userId          用户ID
     * @param tokenValue      会话令牌
     * @param externalHandler 外部响应处理器（可为 null）
     * @return 组合的流式响应处理器
     */
    protected StreamingChatResponseHandler createCombinedHandler(Long userId, String tokenValue,
                                                                  StreamingChatResponseHandler externalHandler) {
        return new StreamingChatResponseHandler() {

            private final StringBuilder messageBuffer = new StringBuilder();

            @SneakyThrows
            @Override
            public void onPartialResponse(String partialResponse) {
                // 1. 追加到缓冲区
                messageBuffer.append(partialResponse);

                // 2. 发送内容事件到 SSE（前端可通过 SSE 监听）
                SseMessageUtils.sendContent(userId, partialResponse);

                // 3. 转发给外部 handler（Workflow 等模块可处理）
                if (externalHandler != null) {
                    externalHandler.onPartialResponse(partialResponse);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                try {
                    // 1. 发送完成事件
                    SseMessageUtils.sendDone(userId);

                    // 2. 关闭 SSE 连接
                    SseMessageUtils.completeConnection(userId, tokenValue);

                    // 3. 转发给外部 handler
                    if (externalHandler != null) {
                        externalHandler.onCompleteResponse(completeResponse);
                    }
                } catch (Exception e) {
                    log.error("完成响应时出错: {}", e.getMessage(), e);
                }
            }

            @Override
            public void onError(Throwable error) {
                // 发送错误事件
                SseMessageUtils.sendError(userId, error.getMessage());
                log.error("流式响应错误: {}", error.getMessage(), error);

                // 转发给外部 handler
                if (externalHandler != null) {
                    externalHandler.onError(error);
                }
            }
        };
    }
}
