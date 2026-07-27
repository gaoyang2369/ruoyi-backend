package org.ruoyi.service.fault;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.dto.request.FaultDiagnosisChatInput;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.domain.enums.agent.AgentExecutionMode;
import org.ruoyi.domain.enums.agent.AgentScenarioCode;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.diagnosis.FaultDiagnosisOrchestrator;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.context.DiagnosisRequestContext;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisObservation;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 聊天层对确定性故障诊断的薄适配，不参与规则、遥测或证据编排。
 */
@Service
@RequiredArgsConstructor
public class FaultDiagnosisChatService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FaultDiagnosisOrchestrator faultDiagnosisOrchestrator;
    private final FaultDiagnosisProperties faultDiagnosisProperties;

    public String diagnose(ChatRequest chatRequest, AgentVo agent, Long userId, String tenantId) {
        DiagnosisCommand command = buildCommand(chatRequest, agent, userId, tenantId);
        return render(faultDiagnosisOrchestrator.diagnose(command));
    }

    DiagnosisCommand buildCommand(ChatRequest chatRequest, AgentVo agent, Long userId, String tenantId) {
        validateAgent(agent);
        if (chatRequest == null || chatRequest.getFaultDiagnosis() == null) {
            throw new ServiceException("故障诊断参数不能为空");
        }

        FaultDiagnosisChatInput input = chatRequest.getFaultDiagnosis();
        LocalDateTime startTime = input.getStartTime();
        LocalDateTime endTime = input.getEndTime();
        if (startTime == null && endTime == null) {
            LocalDateTime now = LocalDateTime.now(ZoneId.of(faultDiagnosisProperties.getTimezone()));
            endTime = now;
            startTime = now.minusMinutes(faultDiagnosisProperties.getDefaultWindowMinutes());
        } else if (startTime == null || endTime == null) {
            throw new ServiceException("故障诊断开始时间和结束时间必须同时提供");
        }

        String symptom = StringUtils.isNotBlank(input.getSymptom()) ? input.getSymptom() : chatRequest.getContent();
        DiagnosisRequestContext context = new DiagnosisRequestContext(agent.getId(), chatRequest.getSessionId(), userId,
            tenantId, UUID.randomUUID().toString());
        return new DiagnosisCommand(input.getDeviceName(), input.getInverterName(), startTime, endTime, symptom,
            agent.getKnowledgeIds() == null ? List.of() : List.copyOf(agent.getKnowledgeIds()), context);
    }

    String render(DiagnosisResult result) {
        Set<String> actualEvidenceCodes = actualEvidenceCodes(result.evidenceIndex());
        StringBuilder text = new StringBuilder("故障诊断结果\n");
        text.append("诊断状态：").append(statusText(result)).append('\n');
        text.append("partial：").append(result.partial() ? "是" : "否").append('\n');
        text.append("设备：").append(valueOrNone(result.deviceName())).append('\n');
        text.append("逆变器：").append(valueOrNone(result.inverterName())).append('\n');
        text.append("实际分析时间：").append(formatTime(result.startTime())).append(" 至 ")
            .append(formatTime(result.endTime())).append('\n');
        text.append("数据质量摘要：").append(dataQualityText(result.dataQuality())).append('\n');
        appendObservations(text, result.observations(), actualEvidenceCodes);
        appendCandidates(text, result.candidateFaults(), actualEvidenceCodes);
        appendStrings(text, "建议", result.recommendations());
        appendLimitations(text, result.limitations(), result.partial());
        text.append("实际证据编号：").append(joinOrNone(actualEvidenceCodes)).append('\n');
        text.append("requestId：").append(valueOrNone(result.requestId()));
        return text.toString();
    }

    private void validateAgent(AgentVo agent) {
        if (agent == null || agent.getId() == null) {
            throw new ServiceException("故障诊断Agent不存在");
        }
        if (!"0".equals(agent.getStatus())) {
            throw new ServiceException("故障诊断Agent未启用: " + agent.getId());
        }
        if (!AgentScenarioCode.FAULT_DIAGNOSIS.name().equals(agent.getScenarioCode())) {
            throw new ServiceException("Agent不是故障诊断场景: " + agent.getId());
        }
        if (!AgentExecutionMode.DETERMINISTIC.name().equals(agent.getExecutionMode())) {
            throw new ServiceException("故障诊断Agent执行方式必须为DETERMINISTIC: " + agent.getId());
        }
    }

    private static void appendObservations(StringBuilder text, List<DiagnosisObservation> observations,
                                           Set<String> actualEvidenceCodes) {
        text.append("观测事实：\n");
        if (observations == null || observations.isEmpty()) {
            text.append("- 无\n");
            return;
        }
        for (DiagnosisObservation observation : observations) {
            text.append("- ").append(valueOrNone(observation.message()));
            appendEvidenceCodes(text, observation.evidenceCodes(), actualEvidenceCodes);
            text.append('\n');
        }
    }

    private static void appendCandidates(StringBuilder text, List<CandidateFault> candidates,
                                         Set<String> actualEvidenceCodes) {
        text.append("候选故障：\n");
        if (candidates == null || candidates.isEmpty()) {
            text.append("- 无\n");
            return;
        }
        for (CandidateFault candidate : candidates) {
            text.append("- ").append(valueOrNone(candidate.faultCode()));
            if (candidate.knowledgeStatus() != null) {
                text.append("（知识查询：").append(candidate.knowledgeStatus()).append('）');
            }
            Set<String> sources = sourceDocuments(candidate.knowledgeEvidence());
            if (!sources.isEmpty()) {
                text.append("；来源文档：").append(String.join("、", sources));
            }
            appendEvidenceCodes(text, candidate.evidenceCodes(), actualEvidenceCodes);
            text.append('\n');
        }
    }

    private static void appendStrings(StringBuilder text, String title, List<String> values) {
        text.append(title).append("：\n");
        if (values == null || values.isEmpty()) {
            text.append("- 无\n");
            return;
        }
        for (String value : values) {
            text.append("- ").append(valueOrNone(value)).append('\n');
        }
    }

    private static void appendLimitations(StringBuilder text, List<String> limitations, boolean partial) {
        text.append("限制说明：\n");
        if (partial) {
            text.append("- 本次结果为降级结果，请结合限制说明谨慎处理。\n");
        }
        if (limitations == null || limitations.isEmpty()) {
            if (!partial) {
                text.append("- 无\n");
            }
            return;
        }
        for (String limitation : limitations) {
            text.append("- ").append(valueOrNone(limitation)).append('\n');
        }
    }

    private static Set<String> actualEvidenceCodes(List<EvidenceReference> evidenceIndex) {
        if (evidenceIndex == null) {
            return Set.of();
        }
        return evidenceIndex.stream()
            .map(EvidenceReference::evidenceCode)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> sourceDocuments(List<FaultKnowledgeEvidence> evidence) {
        if (evidence == null) {
            return Set.of();
        }
        return evidence.stream()
            .map(item -> StringUtils.isNotBlank(item.sourceDocument()) ? item.sourceDocument() : item.documentId())
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static void appendEvidenceCodes(StringBuilder text, List<String> requestedCodes,
                                            Set<String> actualEvidenceCodes) {
        if (requestedCodes == null) {
            return;
        }
        List<String> codes = requestedCodes.stream().filter(actualEvidenceCodes::contains).toList();
        if (!codes.isEmpty()) {
            text.append("；证据：").append(String.join("、", codes));
        }
    }

    private static String statusText(DiagnosisResult result) {
        if (result.status() == null) {
            return "未知";
        }
        return switch (result.status()) {
            case DATA_INSUFFICIENT -> "数据不足";
            case FAULT_DETECTED -> "检测到显式故障";
            case WARNING_DETECTED -> "检测到报警";
            case NO_EXPLICIT_FAULT -> "未发现显式故障";
        };
    }

    private static String dataQualityText(DataQualitySummary quality) {
        if (quality == null) {
            return "无数据质量摘要";
        }
        return "原始记录" + quality.rawRecordCount() + "条，有效记录" + quality.validRecordCount()
            + "条，重复" + quality.duplicateCount() + "条，无效时间" + quality.invalidTimeCount()
            + "条，缺口" + quality.gapCount() + "个，完整度" + quality.completeness()
            + "，数据" + (quality.sufficient() ? "充足" : "不足");
    }

    private static String formatTime(LocalDateTime value) {
        return value == null ? "无" : TIME_FORMATTER.format(value);
    }

    private static String valueOrNone(String value) {
        return StringUtils.isBlank(value) ? "无" : value;
    }

    private static String joinOrNone(Set<String> values) {
        return values == null || values.isEmpty() ? "无" : String.join("、", values);
    }
}
