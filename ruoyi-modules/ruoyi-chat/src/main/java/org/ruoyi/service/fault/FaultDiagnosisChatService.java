package org.ruoyi.service.fault;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
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
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.context.DiagnosisRequestContext;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisObservation;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.fault.model.FaultExecutionResult;
import org.ruoyi.service.fault.model.FaultRequestPlan;
import org.ruoyi.service.fault.model.FaultTaskType;
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
public class FaultDiagnosisChatService {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private final FaultDiagnosisOrchestrator faultDiagnosisOrchestrator;
    private final FaultDiagnosisProperties faultDiagnosisProperties;
    private final FaultRequestPlanner faultRequestPlanner;
    private final FaultAnswerGenerator faultAnswerGenerator;
    private final FaultAnswerSafetyValidator faultAnswerSafetyValidator;
    private final FaultCodeKnowledgeQueryService faultCodeKnowledgeQueryService;
    private final IChatMessageService chatMessageService;

    /** 仅供结构化兼容测试或内部回退使用，不作为生产聊天入口。 */
    @Deprecated
    String diagnose(ChatRequest request, AgentVo agent, Long userId, String tenantId) {
        DiagnosisCommand command = buildCommand(request, agent, userId, tenantId);
        return renderFallback(faultDiagnosisOrchestrator.diagnose(command));
    }

    public String diagnose(ChatRequest request, AgentVo agent, ChatModel model, Long userId, String tenantId) {
        validateAgent(agent);
        FaultRequestPlan plan = request.getFaultDiagnosis() == null ? planFromModel(request, model, userId) : planFromInput(request);
        NormalizedPlan normalized = normalize(plan, request, agent, userId, tenantId);
        if (normalized.clarification() != null) return normalized.clarification();

        DiagnosisResult diagnosis = null;
        if (normalized.plan().tasks().contains(FaultTaskType.DIAGNOSE)) diagnosis = faultDiagnosisOrchestrator.diagnose(normalized.command());
        Map<String, FaultKnowledgeResult> explicit = queryExplicitKnowledge(normalized.plan(), diagnosis, agent.getKnowledgeIds());
        FaultExecutionResult execution = new FaultExecutionResult(normalized.plan(), diagnosis, explicit,
            diagnosis == null ? knowledgeLimitations(normalized.plan(), agent) : diagnosis.limitations());
        if (diagnosis == null && normalized.plan().tasks().contains(FaultTaskType.EXPLAIN_FAULT_CODE)
            && (agent.getKnowledgeIds() == null || agent.getKnowledgeIds().isEmpty())) {
            return renderDeterministicFacts(execution) + "\n\n分析说明：\n当前 Agent 未绑定故障知识库，无法查询故障码知识。"
                + appendEvidenceAndSources(execution);
        }
        String body;
        try {
            body = faultAnswerGenerator.generate(model, request.getContent(), normalized.plan(), execution,
                execution.allowedEvidenceCodes(), agent);
            if (!faultAnswerSafetyValidator.valid(body, execution, diagnosis != null)) throw new IllegalStateException("fault answer safety validation failed");
        } catch (RuntimeException ex) {
            body = diagnosis == null ? renderKnowledgeFallback(explicit) : renderFallback(diagnosis);
        }
        return renderDeterministicFacts(execution) + "\n\n分析说明：\n" + body + appendEvidenceAndSources(execution);
    }

    DiagnosisCommand buildCommand(ChatRequest request, AgentVo agent, Long userId, String tenantId) {
        validateAgent(agent);
        if (request == null || request.getFaultDiagnosis() == null) throw new ServiceException("故障诊断参数不能为空");
        FaultDiagnosisChatInput input = request.getFaultDiagnosis();
        LocalDateTime start = input.getStartTime(), end = input.getEndTime();
        if (start == null && end == null) { end = now(); start = end.minusMinutes(faultDiagnosisProperties.getDefaultWindowMinutes()); }
        else if (start == null || end == null) throw new ServiceException("故障诊断开始时间和结束时间必须同时提供");
        return command(request, agent, userId, tenantId, input.getDeviceName(), input.getInverterName(), start, end,
            StringUtils.isNotBlank(input.getSymptom()) ? input.getSymptom() : request.getContent());
    }

    private FaultRequestPlan planFromModel(ChatRequest request, ChatModel model, Long userId) {
        List<ChatMessage> history = chatMessageService.getMessagesBySessionIdAndUserId(request.getSessionId(), userId, 12);
        return faultRequestPlanner.plan(model, history, now(), faultDiagnosisProperties.getTimezone(),
            faultDiagnosisProperties.getDefaultWindowMinutes(), faultDiagnosisProperties.getAllowedAssets(), request.getContent(), UUID.randomUUID().toString());
    }

    private FaultRequestPlan planFromInput(ChatRequest request) {
        FaultDiagnosisChatInput input = request.getFaultDiagnosis();
        return new FaultRequestPlan(List.of(FaultTaskType.DIAGNOSE), input.getDeviceName(), input.getInverterName(), null,
            formatTime(input.getStartTime()), formatTime(input.getEndTime()), List.of(), input.getSymptom(), List.of());
    }

    private NormalizedPlan normalize(FaultRequestPlan proposed, ChatRequest request, AgentVo agent, Long userId, String tenantId) {
        Set<FaultTaskType> tasks = new LinkedHashSet<>(proposed.tasks());
        if (tasks.isEmpty()) {
            if (StringUtils.isNotBlank(proposed.deviceName()) || StringUtils.isNotBlank(proposed.inverterName()) || proposed.recentMinutes() != null || StringUtils.isNotBlank(proposed.startTime()) || StringUtils.isNotBlank(proposed.symptom())) tasks.add(FaultTaskType.DIAGNOSE);
            if (!proposed.faultCodes().isEmpty()) tasks.add(FaultTaskType.EXPLAIN_FAULT_CODE);
        }
        if (tasks.isEmpty()) return NormalizedPlan.clarify("请说明需要诊断的设备、逆变器、时间范围，或明确要查询的故障码。");
        if (tasks.size() > 2) return NormalizedPlan.clarify("本次仅支持诊断设备遥测和查询故障码知识。");
        List<String> codes;
        try { codes = proposed.faultCodes().stream().map(FaultKnowledgeQuery::normalizeFaultCode).distinct().toList(); }
        catch (IllegalArgumentException ex) { return NormalizedPlan.clarify("故障码格式无效，请提供例如 F30005 的故障码。"); }
        if (tasks.contains(FaultTaskType.EXPLAIN_FAULT_CODE) && codes.isEmpty()) return NormalizedPlan.clarify("请提供需要说明的故障码。");
        if (!tasks.contains(FaultTaskType.DIAGNOSE)) return NormalizedPlan.ready(new FaultRequestPlan(List.copyOf(tasks), proposed.deviceName(), proposed.inverterName(), proposed.recentMinutes(), proposed.startTime(), proposed.endTime(), codes, proposed.symptom(), proposed.requestedAspects()), null);
        if (StringUtils.isBlank(proposed.deviceName()) || StringUtils.isBlank(proposed.inverterName())) return NormalizedPlan.clarify("请补充需要诊断的设备名称和逆变器名称。");
        TimeRange range = timeRange(proposed);
        if (range == null) return NormalizedPlan.clarify("请同时提供开始和结束时间，或提供有效的最近分钟数。");
        FaultRequestPlan plan = new FaultRequestPlan(List.copyOf(tasks), proposed.deviceName(), proposed.inverterName(), proposed.recentMinutes(), formatTime(range.start()), formatTime(range.end()), codes, proposed.symptom(), proposed.requestedAspects());
        return NormalizedPlan.ready(plan, command(request, agent, userId, tenantId, plan.deviceName(), plan.inverterName(), range.start(), range.end(), StringUtils.isNotBlank(plan.symptom()) ? plan.symptom() : request.getContent()));
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
        return new DiagnosisCommand(device, inverter, start, end, symptom, agent.getKnowledgeIds() == null ? List.of() : List.copyOf(agent.getKnowledgeIds()),
            new DiagnosisRequestContext(agent.getId(), request.getSessionId(), userId, tenantId, UUID.randomUUID().toString()));
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of(faultDiagnosisProperties.getTimezone())); }
    private void validateAgent(AgentVo agent) {
        if (agent == null || agent.getId() == null) throw new ServiceException("故障诊断Agent不存在");
        if (!"0".equals(agent.getStatus())) throw new ServiceException("故障诊断Agent未启用: " + agent.getId());
        if (!AgentScenarioCode.FAULT_DIAGNOSIS.name().equals(agent.getScenarioCode())) throw new ServiceException("Agent不是故障诊断场景: " + agent.getId());
        if (!AgentExecutionMode.DETERMINISTIC.name().equals(agent.getExecutionMode())) throw new ServiceException("故障诊断Agent执行方式必须为DETERMINISTIC: " + agent.getId());
    }

    String renderFallback(DiagnosisResult result) {
        Set<String> actual = actualEvidenceCodes(result.evidenceIndex()); StringBuilder text = new StringBuilder("故障诊断结果\n");
        text.append("诊断状态：").append(statusText(result)).append('\n').append("partial：").append(result.partial() ? "是" : "否").append('\n');
        text.append("设备：").append(valueOrNone(result.deviceName())).append('\n').append("逆变器：").append(valueOrNone(result.inverterName())).append('\n');
        text.append("实际分析时间：").append(formatTime(result.startTime())).append(" 至 ").append(formatTime(result.endTime())).append('\n');
        text.append("数据质量摘要：").append(dataQualityText(result.dataQuality())).append('\n');
        appendObservations(text, result.observations(), actual); appendCandidates(text, result.candidateFaults(), actual); appendStrings(text, "建议", result.recommendations()); appendLimitations(text, result.limitations(), result.partial());
        text.append("实际证据编号：").append(joinOrNone(actual)).append('\n').append("requestId：").append(valueOrNone(result.requestId())); return text.toString();
    }
    /** 保留原包内测试兼容名。 */ String render(DiagnosisResult result) { return renderFallback(result); }
    private static String renderKnowledgeFallback(Map<String, FaultKnowledgeResult> results) { return results.isEmpty() ? "当前 Agent 未绑定故障知识库，无法查询故障码知识。" : "以下为用户单独查询的故障码知识，不代表本次设备遥测已出现该故障码。"; }
    private static String renderDeterministicFacts(FaultExecutionResult execution) {
        DiagnosisResult result = execution.diagnosisResult();
        StringBuilder out = new StringBuilder("确定性诊断事实：\n");
        if (result == null) out.append("诊断状态：未执行设备遥测诊断\n");
        else {
            out.append("诊断状态：").append(statusText(result)).append('\n');
            out.append("设备：").append(valueOrNone(result.deviceName())).append('\n');
            out.append("实际分析时间：").append(formatTime(result.startTime())).append(" 至 ").append(formatTime(result.endTime())).append('\n');
            out.append("数据质量摘要：").append(dataQualityText(result.dataQuality())).append('\n');
        }
        if (execution.observedFaultCodes().isEmpty()) out.append("本次遥测范围内未确认出现显式故障码。\n");
        else out.append("本次遥测实际观测到的故障码：").append(String.join("、", execution.observedFaultCodes())).append('\n');
        if (!execution.queriedOnlyFaultCodes().isEmpty()) out.append("以下故障码仅进行知识查询，未确认在本次遥测范围内出现：")
            .append(String.join("、", execution.queriedOnlyFaultCodes())).append("。\n");
        return out.toString().trim();
    }
    private static String appendEvidenceAndSources(FaultExecutionResult execution) {
        StringBuilder out = new StringBuilder("\n\n证据与来源：\n"); Set<String> codes = execution.allowedEvidenceCodes();
        if (!codes.isEmpty()) out.append("真实证据编号：").append(String.join("、", codes)).append('\n');
        for (FaultExecutionResult.KnowledgeSource source : execution.knowledgeSourcesWithFaultCode()) {
            FaultKnowledgeEvidence item = source.evidence();
            out.append("知识来源：").append(valueOrNone(source.faultCode())).append(" - ")
            .append(StringUtils.isNotBlank(item.sourceDocument()) ? item.sourceDocument() : valueOrNone(item.documentId()))
            .append(item.fragmentId() == null ? "" : " / " + item.fragmentId()).append('\n');
        }
        if (codes.isEmpty() && execution.knowledgeSourcesWithFaultCode().isEmpty()) out.append("本次没有可引用的持久化证据或知识来源"); return out.toString().trim();
    }
    private static void appendObservations(StringBuilder text, List<DiagnosisObservation> observations, Set<String> actual) { text.append("观测事实：\n"); if (observations == null || observations.isEmpty()) { text.append("- 无\n"); return; } for (DiagnosisObservation o : observations) { text.append("- ").append(valueOrNone(o.message())); appendEvidenceCodes(text, o.evidenceCodes(), actual); text.append('\n'); } }
    private static void appendCandidates(StringBuilder text, List<CandidateFault> candidates, Set<String> actual) { text.append("候选故障：\n"); if (candidates == null || candidates.isEmpty()) { text.append("- 无\n"); return; } for (CandidateFault c : candidates) { text.append("- ").append(valueOrNone(c.faultCode())); if (c.knowledgeStatus() != null) text.append("（知识查询：").append(c.knowledgeStatus()).append('）'); Set<String> sources = sourceDocuments(c.knowledgeEvidence()); if (!sources.isEmpty()) text.append("；来源文档：").append(String.join("、", sources)); appendEvidenceCodes(text, c.evidenceCodes(), actual); text.append('\n'); } }
    private static void appendStrings(StringBuilder text, String title, List<String> values) { text.append(title).append("：\n"); if (values == null || values.isEmpty()) { text.append("- 无\n"); return; } for (String value : values) text.append("- ").append(valueOrNone(value)).append('\n'); }
    private static void appendLimitations(StringBuilder text, List<String> values, boolean partial) { text.append("限制说明：\n"); if (partial) text.append("- 本次结果为降级结果，请结合限制说明谨慎处理。\n"); if (values == null || values.isEmpty()) { if (!partial) text.append("- 无\n"); return; } for (String value : values) text.append("- ").append(valueOrNone(value)).append('\n'); }
    private static Set<String> actualEvidenceCodes(List<EvidenceReference> evidence) { return evidence == null ? Set.of() : evidence.stream().map(EvidenceReference::evidenceCode).filter(StringUtils::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new)); }
    private static Set<String> sourceDocuments(List<FaultKnowledgeEvidence> evidence) { return evidence == null ? Set.of() : evidence.stream().map(item -> StringUtils.isNotBlank(item.sourceDocument()) ? item.sourceDocument() : item.documentId()).filter(StringUtils::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new)); }
    private static void appendEvidenceCodes(StringBuilder text, List<String> requested, Set<String> actual) { if (requested == null) return; List<String> codes = requested.stream().filter(actual::contains).toList(); if (!codes.isEmpty()) text.append("；证据：").append(String.join("、", codes)); }
    private static String statusText(DiagnosisResult result) { return result.status() == null ? "未知" : switch (result.status()) { case DATA_INSUFFICIENT -> "数据不足"; case FAULT_DETECTED -> "检测到显式故障"; case WARNING_DETECTED -> "检测到报警"; case NO_EXPLICIT_FAULT -> "未发现显式故障"; }; }
    private static String dataQualityText(DataQualitySummary quality) { return quality == null ? "无数据质量摘要" : "原始记录" + quality.rawRecordCount() + "条，有效记录" + quality.validRecordCount() + "条，重复" + quality.duplicateCount() + "条，无效时间" + quality.invalidTimeCount() + "条，缺口" + quality.gapCount() + "个，完整度" + quality.completeness() + "，数据" + (quality.sufficient() ? "充足" : "不足"); }
    private static String formatTime(LocalDateTime value) { return value == null ? "无" : TIME_FORMATTER.format(value); }
    private static String valueOrNone(String value) { return StringUtils.isBlank(value) ? "无" : value; }
    private static String joinOrNone(Set<String> values) { return values == null || values.isEmpty() ? "无" : String.join("、", values); }
    private record TimeRange(LocalDateTime start, LocalDateTime end) { }
    private record NormalizedPlan(FaultRequestPlan plan, DiagnosisCommand command, String clarification) { static NormalizedPlan ready(FaultRequestPlan p, DiagnosisCommand c) { return new NormalizedPlan(p, c, null); } static NormalizedPlan clarify(String message) { return new NormalizedPlan(null, null, message); } }
}
