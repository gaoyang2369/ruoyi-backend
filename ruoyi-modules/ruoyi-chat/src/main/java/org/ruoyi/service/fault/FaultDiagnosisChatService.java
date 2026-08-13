package org.ruoyi.service.fault;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.dto.request.FaultDiagnosisChatInput;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.domain.enums.agent.AgentExecutionMode;
import org.ruoyi.domain.enums.agent.AgentScenarioCode;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.fault.application.FaultCodeKnowledgeQueryService;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.diagnosis.FaultDiagnosisOrchestrator;
import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.context.DiagnosisRequestContext;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.fault.report.OperationReportResult;
import org.ruoyi.fault.telemetry.service.TelemetryQueryService;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.fault.model.FaultExecutionResult;
import org.ruoyi.service.fault.model.FaultKnowledgeAnswerDraft;
import org.ruoyi.service.fault.model.FaultKnowledgeFacts;
import org.ruoyi.service.fault.model.FaultRequestPlan;
import org.ruoyi.service.fault.model.FaultTaskType;
import org.ruoyi.service.fault.model.FaultReportAttachment;
import org.ruoyi.service.fault.model.FaultReportChatResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 聊天层协调器：LLM 只规划/表达，遥测、规则、知识范围和证据均由确定性后端控制。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaultDiagnosisChatService {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private final FaultDiagnosisOrchestrator faultDiagnosisOrchestrator;
    private final FaultDiagnosisProperties faultDiagnosisProperties;
    private final FaultRequestPlanner faultRequestPlanner;
    private final FaultAnswerGenerator faultAnswerGenerator;
    private final FaultAnswerSafetyValidator faultAnswerSafetyValidator;
    private final FaultCodeKnowledgeQueryService faultCodeKnowledgeQueryService;
    private final IChatMessageService chatMessageService;
    private final TelemetryQueryService telemetryQueryService;
    private final OperationReportService operationReportService;

    /** 使用门面已识别的报告计划生成一次快照；事实确定后再由 Agent 绑定模型做受约束归纳。 */
    public FaultReportChatResult generateReport(ChatRequest request, AgentVo agent, FaultRequestPlan reportPlan,
                                                Long userId, String tenantId) {
        validateAgent(agent);
        if (reportPlan == null || !reportPlan.tasks().contains(FaultTaskType.GENERATE_REPORT)) {
            throw new ServiceException("运行报告计划无效");
        }
        String requestId = UUID.randomUUID().toString();
        NormalizedPlan normalized = normalize(reportPlan, request, agent, userId, tenantId, requestId);
        if (normalized.clarification() != null) {
            return new FaultReportChatResult(normalized.clarification(), null, false);
        }
        return createReport(request, normalized.command(), userId, tenantId);
    }

    private FaultReportChatResult createReport(ChatRequest request, DiagnosisCommand command,
                                               Long userId, String tenantId) {
        OperationReportResult facts = operationReportService.prepare(command, request.getSessionId(), userId, tenantId);
        OperationReportResult.ReportNarrative narrative = operationReportService.narrate(command.context().agentId(), facts);
        OperationReportResult report = narrative == null
            ? operationReportService.completeFallback(facts.metadata().reportId(), userId, tenantId)
            : operationReportService.finalize(facts.metadata().reportId(), narrative, userId, tenantId);
        double completeness = report.dataQuality() == null ? 0D : report.dataQuality().completeness();
        FaultReportAttachment attachment = new FaultReportAttachment(
            report.metadata().reportId(),
            report.asset().deviceName() + "运行报告",
            report.asset().deviceName(),
            report.asset().inverterName(),
            report.period().windowStart(),
            report.period().windowEnd(),
            OperationReportSnapshotService.STATUS_COMPLETED,
            report.currentStatus().name(),
            report.periodStatus().name(),
            completeness);
        return new FaultReportChatResult("运行报告已生成。" + report.summary().conclusion(), attachment,
            narrative != null);
    }

    /** 仅供结构化兼容测试或内部回退使用，不作为生产聊天入口。 */
    @Deprecated
    String diagnose(ChatRequest request, AgentVo agent, Long userId, String tenantId) {
        DiagnosisCommand command = buildCommand(request, agent, userId, tenantId);
        return renderFallback(faultDiagnosisOrchestrator.diagnose(command));
    }

    public String diagnose(ChatRequest request, AgentVo agent, ChatModel model, Long userId, String tenantId) {
        long startNanos = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        validateAgent(agent);
        FaultRequestPlan plan = request.getFaultDiagnosis() == null
            ? planFromModel(request, model, userId, requestId) : planFromInput(request);
        NormalizedPlan normalized = normalize(plan, request, agent, userId, tenantId, requestId);
        if (normalized.clarification() != null) return normalized.clarification();

        // 兼容入口同样只返回短结论；完整报告通过持久化快照和 report SSE 事件交付。
        if (normalized.plan().tasks().contains(FaultTaskType.GENERATE_REPORT)) {
            return createReport(request, normalized.command(), userId, tenantId).content();
        }

        DiagnosisResult diagnosis = null;
        if (normalized.plan().tasks().contains(FaultTaskType.DIAGNOSE)) diagnosis = faultDiagnosisOrchestrator.diagnose(normalized.command());
        Map<String, FaultKnowledgeResult> explicit = queryExplicitKnowledge(normalized.plan(), diagnosis, agent.getKnowledgeIds());
        FaultExecutionResult execution = new FaultExecutionResult(normalized.plan(), diagnosis, explicit,
            diagnosis == null ? knowledgeLimitations(normalized.plan(), agent) : diagnosis.limitations());
        if (diagnosis == null && normalized.plan().tasks().contains(FaultTaskType.EXPLAIN_FAULT_CODE)) {
            return answerKnowledgeQuery(request, agent, model, normalized.plan(), execution, requestId, startNanos);
        }
        // 结论、时间边界、观测列表和证据摘要由服务端确定性渲染；模型只生成代码说明与建议。
        String body = generateValidatedAnswer(model, request.getContent(), normalized.plan(), execution, agent,
            true, requestId, startNanos);
        return FaultDiagnosisAnswerRenderer.render(execution, body);
    }

    private String answerKnowledgeQuery(ChatRequest request, AgentVo agent, ChatModel model, FaultRequestPlan plan,
                                        FaultExecutionResult execution, String requestId, long startNanos) {
        Map<String, FaultKnowledgeResult> results = execution.explicitKnowledgeResults();
        List<FaultKnowledgeFacts> facts = SiemensFaultKnowledgeExtractor.extract(plan.faultCodes(), results);
        String fallback = renderKnowledgeFallback(plan.faultCodes(), results, agent.getKnowledgeIds(), facts);
        if (!allKnowledgeMatched(plan.faultCodes(), results)) return fallback;
        String body = generateValidatedKnowledgeAnswer(model, request.getContent(), plan, execution, agent, facts,
            requestId, startNanos);
        return body == null ? fallback : renderKnowledgeModelAnswer(plan.faultCodes(), body, results);
    }

    private String generateValidatedKnowledgeAnswer(ChatModel model, String question, FaultRequestPlan plan,
                                                    FaultExecutionResult execution, AgentVo agent,
                                                    List<FaultKnowledgeFacts> facts, String requestId,
                                                    long startNanos) {
        FaultKnowledgeAnswerDraft draft;
        try {
            draft = faultAnswerGenerator.generateKnowledgeDraft(model, question, plan, facts, agent);
        } catch (RuntimeException ex) {
            logFallback(requestId, plan, execution, startNanos, FallbackReason.MODEL_EXCEPTION, ex);
            return null;
        }
        if (draft == null) {
            logFallback(requestId, plan, execution, startNanos, FallbackReason.EMPTY_MODEL_ANSWER, null);
            return null;
        }
        String body;
        try {
            if (!FaultKnowledgeAnswerRenderer.valid(draft, facts, plan.faultCodes())) {
                logFallback(requestId, plan, execution, startNanos,
                    FallbackReason.SAFETY_VALIDATION_REJECTED, null);
                return null;
            }
            body = FaultKnowledgeAnswerRenderer.renderDraft(draft, facts);
            if (!faultAnswerSafetyValidator.valid(body, execution, false)) {
                logFallback(requestId, plan, execution, startNanos,
                    FallbackReason.SAFETY_VALIDATION_REJECTED, null);
                return null;
            }
        } catch (RuntimeException ex) {
            logFallback(requestId, plan, execution, startNanos,
                FallbackReason.SAFETY_VALIDATION_REJECTED, ex);
            return null;
        }
        return body;
    }

    private String generateValidatedAnswer(ChatModel model, String question, FaultRequestPlan plan,
                                           FaultExecutionResult execution, AgentVo agent, boolean diagnosisExecuted,
                                           String requestId, long startNanos) {
        String body;
        try {
            body = faultAnswerGenerator.generate(model, question, plan, execution,
                execution.allowedEvidenceCodes(), agent);
        } catch (RuntimeException ex) {
            logFallback(requestId, plan, execution, startNanos, FallbackReason.MODEL_EXCEPTION, ex);
            return null;
        }
        if (StringUtils.isBlank(body)) {
            logFallback(requestId, plan, execution, startNanos, FallbackReason.EMPTY_MODEL_ANSWER, null);
            return null;
        }
        boolean valid;
        try {
            valid = faultAnswerSafetyValidator.valid(body, execution, diagnosisExecuted);
        } catch (RuntimeException ex) {
            logFallback(requestId, plan, execution, startNanos, FallbackReason.SAFETY_VALIDATION_REJECTED, ex);
            return null;
        }
        if (!valid) {
            logFallback(requestId, plan, execution, startNanos, FallbackReason.SAFETY_VALIDATION_REJECTED, null);
            return null;
        }
        return body;
    }

    private static void logFallback(String requestId, FaultRequestPlan plan, FaultExecutionResult execution,
                                    long startNanos, FallbackReason reason, RuntimeException exception) {
        Set<String> faultCodes = new LinkedHashSet<>(plan.faultCodes());
        faultCodes.addAll(execution.observedFaultCodes());
        faultCodes.addAll(execution.observedAlarmCodes());
        log.warn("故障回答进入降级路径: requestId={}, taskType={}, faultCode={}, elapsedMs={}, "
                + "fallbackReason={}, errorType={}",
            requestId, plan.tasks(), faultCodes, elapsedMillis(startNanos), reason,
            exception == null ? "none" : exception.getClass().getSimpleName());
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    DiagnosisCommand buildCommand(ChatRequest request, AgentVo agent, Long userId, String tenantId) {
        validateAgent(agent);
        if (request == null || request.getFaultDiagnosis() == null) throw new ServiceException("故障诊断参数不能为空");
        FaultDiagnosisChatInput input = request.getFaultDiagnosis();
        LocalDateTime start = input.getStartTime(), end = input.getEndTime();
        if (start == null && end == null) { end = now(); start = end.minusMinutes(faultDiagnosisProperties.getDefaultWindowMinutes()); }
        else if (start == null || end == null) throw new ServiceException("故障诊断开始时间和结束时间必须同时提供");
        return command(request, agent, userId, tenantId,
            FaultAssetNameResolver.canonicalize(input.getDeviceName(), faultDiagnosisProperties.getAllowedAssets()), input.getInverterName(), start, end,
            StringUtils.isNotBlank(input.getSymptom()) ? input.getSymptom() : request.getContent());
    }

    private FaultRequestPlan planFromModel(ChatRequest request, ChatModel model, Long userId, String requestId) {
        List<ChatMessage> history = chatMessageService.getMessagesBySessionIdAndUserId(request.getSessionId(), userId, 12);
        return faultRequestPlanner.plan(model, history, now(), faultDiagnosisProperties.getTimezone(),
            faultDiagnosisProperties.getDefaultWindowMinutes(), faultDiagnosisProperties.getAllowedAssets(),
            request.getContent(), requestId);
    }

    private FaultRequestPlan planFromInput(ChatRequest request) {
        FaultDiagnosisChatInput input = request.getFaultDiagnosis();
        return new FaultRequestPlan(List.of(FaultTaskType.DIAGNOSE), input.getDeviceName(), input.getInverterName(), null,
            formatTime(input.getStartTime()), formatTime(input.getEndTime()), List.of(), input.getSymptom(), List.of());
    }

    private NormalizedPlan normalize(FaultRequestPlan proposed, ChatRequest request, AgentVo agent, Long userId,
                                     String tenantId, String requestId) {
        Set<FaultTaskType> tasks = new LinkedHashSet<>(proposed.tasks());
        if (tasks.isEmpty()) {
            if (StringUtils.isNotBlank(proposed.deviceName()) || StringUtils.isNotBlank(proposed.inverterName()) || proposed.recentMinutes() != null || StringUtils.isNotBlank(proposed.startTime()) || StringUtils.isNotBlank(proposed.symptom())) tasks.add(FaultTaskType.DIAGNOSE);
            if (!proposed.faultCodes().isEmpty()) tasks.add(FaultTaskType.EXPLAIN_FAULT_CODE);
        }
        if (tasks.isEmpty()) return NormalizedPlan.clarify("请说明需要诊断的设备、逆变器、时间范围，或明确要查询的故障码。");
        // 报告已内含诊断：收敛为单一报告任务，避免重复执行诊断链路。
        if (tasks.contains(FaultTaskType.GENERATE_REPORT)) {
            tasks.clear();
            tasks.add(FaultTaskType.GENERATE_REPORT);
        }
        if (tasks.size() > 2) return NormalizedPlan.clarify("本次仅支持诊断设备遥测和查询故障码知识。");
        List<String> codes = List.of();
        if (tasks.contains(FaultTaskType.EXPLAIN_FAULT_CODE)) {
            try { codes = proposed.faultCodes().stream().map(FaultKnowledgeQuery::normalizeFaultCode).distinct().toList(); }
            catch (IllegalArgumentException ex) { return NormalizedPlan.clarify("故障码格式无效，请提供例如 F30005 的故障码。"); }
        }
        if (tasks.contains(FaultTaskType.EXPLAIN_FAULT_CODE) && codes.isEmpty()) return NormalizedPlan.clarify("请提供需要说明的故障码。");
        if (!tasks.contains(FaultTaskType.DIAGNOSE) && !tasks.contains(FaultTaskType.GENERATE_REPORT)) return NormalizedPlan.ready(new FaultRequestPlan(List.copyOf(tasks), proposed.deviceName(), proposed.inverterName(), proposed.recentMinutes(), proposed.startTime(), proposed.endTime(), codes, proposed.symptom(), proposed.requestedAspects()), null);
        if (StringUtils.isBlank(proposed.deviceName())) return NormalizedPlan.clarify(
            tasks.contains(FaultTaskType.GENERATE_REPORT) ? "请补充需要生成报告的设备名称。" : "请补充需要诊断的设备名称。");
        String deviceName = FaultAssetNameResolver.canonicalize(proposed.deviceName(),
            faultDiagnosisProperties.getAllowedAssets());
        TimeRange range = timeRange(proposed);
        if (range == null) return NormalizedPlan.clarify("请同时提供开始和结束时间，或提供有效的最近分钟数。");
        // 逆变器可选：用户未指明时由遥测数据确定性补全，而不是要求用户补充一个他们通常不知道的名称。
        String inverterName = proposed.inverterName();
        if (StringUtils.isBlank(inverterName)) {
            try {
                inverterName = telemetryQueryService.resolveInverterName(deviceName);
            } catch (ServiceException ex) {
                return NormalizedPlan.clarify(ex.getMessage());
            }
        }
        FaultRequestPlan plan = new FaultRequestPlan(List.copyOf(tasks), deviceName, inverterName, proposed.recentMinutes(), formatTime(range.start()), formatTime(range.end()), codes, proposed.symptom(), proposed.requestedAspects());
        return NormalizedPlan.ready(plan, command(request, agent, userId, tenantId, plan.deviceName(),
            plan.inverterName(), range.start(), range.end(),
            StringUtils.isNotBlank(plan.symptom()) ? plan.symptom() : request.getContent(), requestId));
    }

    private TimeRange timeRange(FaultRequestPlan plan) {
        if (StringUtils.isNotBlank(plan.startTime()) || StringUtils.isNotBlank(plan.endTime())) {
            if (StringUtils.isBlank(plan.startTime()) || StringUtils.isBlank(plan.endTime())) return null;
            try { return new TimeRange(LocalDateTime.parse(plan.startTime(), TIME_FORMATTER), LocalDateTime.parse(plan.endTime(), TIME_FORMATTER)); }
            catch (DateTimeParseException ex) { return null; }
        }
        LocalDateTime end = now();
        if (plan.recentMinutes() != null) return plan.recentMinutes() > 0 ? new TimeRange(end.minusMinutes(plan.recentMinutes()), end) : null;
        return new TimeRange(end.minusMinutes(faultDiagnosisProperties.getDefaultWindowMinutes()), end);
    }

    private Map<String, FaultKnowledgeResult> queryExplicitKnowledge(FaultRequestPlan plan, DiagnosisResult diagnosis, List<Long> knowledgeIds) {
        if (!plan.tasks().contains(FaultTaskType.EXPLAIN_FAULT_CODE)) return Map.of();
        if (knowledgeIds == null || knowledgeIds.isEmpty()) return Map.of();
        Set<String> existing = diagnosis == null ? Set.of() : diagnosis.candidateFaults().stream().map(CandidateFault::faultCode).collect(Collectors.toSet());
        Map<String, FaultKnowledgeResult> results = new LinkedHashMap<>();
        for (String code : plan.faultCodes()) if (!existing.contains(code)) results.put(code, faultCodeKnowledgeQueryService.query(code, List.copyOf(knowledgeIds)));
        return results;
    }
    private static List<String> knowledgeLimitations(FaultRequestPlan plan, AgentVo agent) {
        return plan.tasks().contains(FaultTaskType.EXPLAIN_FAULT_CODE) && (agent.getKnowledgeIds() == null || agent.getKnowledgeIds().isEmpty())
            ? List.of("当前 Agent 未绑定故障知识库") : List.of();
    }

    private DiagnosisCommand command(ChatRequest request, AgentVo agent, Long userId, String tenantId, String device, String inverter, LocalDateTime start, LocalDateTime end, String symptom) {
        return command(request, agent, userId, tenantId, device, inverter, start, end, symptom,
            UUID.randomUUID().toString());
    }
    private DiagnosisCommand command(ChatRequest request, AgentVo agent, Long userId, String tenantId, String device,
                                     String inverter, LocalDateTime start, LocalDateTime end, String symptom,
                                     String requestId) {
        return new DiagnosisCommand(device, inverter, start, end, symptom, agent.getKnowledgeIds() == null ? List.of() : List.copyOf(agent.getKnowledgeIds()),
            new DiagnosisRequestContext(agent.getId(), request.getSessionId(), userId, tenantId, requestId));
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of(faultDiagnosisProperties.getTimezone())); }
    private void validateAgent(AgentVo agent) {
        if (agent == null || agent.getId() == null) throw new ServiceException("故障诊断Agent不存在");
        if (!"0".equals(agent.getStatus())) throw new ServiceException("故障诊断Agent未启用: " + agent.getId());
        if (!AgentScenarioCode.FAULT_DIAGNOSIS.name().equals(agent.getScenarioCode())) throw new ServiceException("Agent不是故障诊断场景: " + agent.getId());
        if (!AgentExecutionMode.DETERMINISTIC.name().equals(agent.getExecutionMode())) throw new ServiceException("故障诊断Agent执行方式必须为DETERMINISTIC: " + agent.getId());
    }

    /** 降级回答同样使用统一骨架：模型部分由服务端确定性内容填充，不展示内部字段。 */
    String renderFallback(DiagnosisResult result) {
        FaultExecutionResult execution = new FaultExecutionResult(null, result, Map.of(), result.limitations());
        return FaultDiagnosisAnswerRenderer.render(execution, null);
    }
    /** 保留原包内测试兼容名。 */ String render(DiagnosisResult result) { return renderFallback(result); }
    private static String renderKnowledgeFallback(List<String> faultCodes, Map<String, FaultKnowledgeResult> results,
                                                  List<Long> knowledgeIds,
                                                  List<FaultKnowledgeFacts> facts) {
        Map<String, FaultKnowledgeFacts> factsByCode = facts.stream()
            .collect(Collectors.toMap(FaultKnowledgeFacts::faultCode, item -> item,
                (left, right) -> left, LinkedHashMap::new));
        StringBuilder out = new StringBuilder();
        for (String faultCode : faultCodes) {
            if (!out.isEmpty()) out.append("\n\n");
            out.append(FaultCodeType.isAlarm(faultCode) ? "报警码知识查询：" : "故障码知识查询：").append(faultCode).append('\n');
            if (knowledgeIds == null || knowledgeIds.isEmpty()) {
                out.append("\n当前 Agent 未绑定故障知识库，无法查询该故障码。");
                continue;
            }
            FaultKnowledgeResult result = results.get(faultCode);
            if (result == null || result.status() == FaultKnowledgeResult.Status.FAILED) {
                out.append("\n知识查询失败，请稍后重试。");
            } else if (result.status() == FaultKnowledgeResult.Status.NOT_FOUND) {
                out.append("\n已查询绑定的故障知识库，但未找到与该故障码精确匹配的内容。");
            } else {
                FaultKnowledgeFacts fact = factsByCode.get(faultCode);
                if (fact == null) {
                    out.append("\n未能从匹配内容中提取结构化章节。");
                } else {
                    out.append('\n').append(FaultKnowledgeAnswerRenderer.renderFallback(List.of(fact)));
                }
                appendKnowledgeSources(out, faultCode, result.evidence());
            }
        }
        appendKnowledgeBoundary(out);
        return out.toString();
    }

    private static String renderKnowledgeModelAnswer(List<String> faultCodes, String body,
                                                     Map<String, FaultKnowledgeResult> results) {
        boolean alarmOnly = !faultCodes.isEmpty() && faultCodes.stream().allMatch(FaultCodeType::isAlarm);
        StringBuilder out = new StringBuilder(alarmOnly ? "报警码知识查询：" : "故障码知识查询：")
            .append(String.join("、", faultCodes))
            .append("\n\n")
            .append(body.trim());
        for (String faultCode : faultCodes) {
            FaultKnowledgeResult result = results.get(faultCode);
            if (result != null) appendKnowledgeSources(out, faultCode, result.evidence());
        }
        appendKnowledgeBoundary(out);
        return out.toString();
    }

    private static boolean allKnowledgeMatched(List<String> faultCodes, Map<String, FaultKnowledgeResult> results) {
        return !faultCodes.isEmpty() && faultCodes.stream()
            .map(results::get)
            .allMatch(result -> result != null && result.status() == FaultKnowledgeResult.Status.MATCHED);
    }

    private static void appendKnowledgeSources(StringBuilder out, String faultCode,
                                               List<FaultKnowledgeEvidence> evidence) {
        Set<String> sources = new LinkedHashSet<>();
        for (FaultKnowledgeEvidence item : evidence) {
            String document = StringUtils.isNotBlank(item.sourceDocument())
                ? item.sourceDocument() : valueOrNone(item.documentId());
            sources.add(document + (StringUtils.isBlank(item.fragmentId()) ? "" : " / " + item.fragmentId()));
        }
        if (!sources.isEmpty()) {
            out.append("\n来源：").append(faultCode).append(" - ").append(String.join("、", sources));
        }
    }

    private static void appendKnowledgeBoundary(StringBuilder out) {
        out.append("\n\n说明：本次仅查询故障手册，未读取设备遥测数据。");
    }

    private static String formatTime(LocalDateTime value) { return value == null ? "无" : TIME_FORMATTER.format(value); }
    private static String valueOrNone(String value) { return StringUtils.isBlank(value) ? "无" : value; }
    private enum FallbackReason { MODEL_EXCEPTION, EMPTY_MODEL_ANSWER, SAFETY_VALIDATION_REJECTED }
    private record TimeRange(LocalDateTime start, LocalDateTime end) { }
    private record NormalizedPlan(FaultRequestPlan plan, DiagnosisCommand command, String clarification) { static NormalizedPlan ready(FaultRequestPlan p, DiagnosisCommand c) { return new NormalizedPlan(p, c, null); } static NormalizedPlan clarify(String message) { return new NormalizedPlan(null, null, message); } }
}
