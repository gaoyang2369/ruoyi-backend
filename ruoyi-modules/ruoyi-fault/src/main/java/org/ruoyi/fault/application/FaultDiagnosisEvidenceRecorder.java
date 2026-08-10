package org.ruoyi.fault.application;

import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.result.DiagnosisDecision;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.evidence.entity.DiagnosisCaseEntity;
import org.ruoyi.fault.evidence.entity.DiagnosisStepEntity;
import org.ruoyi.fault.evidence.enums.DiagnosisStepType;
import org.ruoyi.fault.evidence.enums.EvidenceType;
import org.ruoyi.fault.evidence.model.DiagnosisCaseCreateCommand;
import org.ruoyi.fault.evidence.model.DiagnosisStepStartCommand;
import org.ruoyi.fault.evidence.model.EvidenceAppendCommand;
import org.ruoyi.fault.evidence.model.EvidenceAppendResult;
import org.ruoyi.fault.evidence.service.DiagnosisCaseService;
import org.ruoyi.fault.evidence.service.DiagnosisStepService;
import org.ruoyi.fault.evidence.service.EvidenceChainService;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对提交五已存在证据服务的薄适配层。证据记录失败不伪造编号，且不阻断确定性核心诊断。
 */
@Component
public class FaultDiagnosisEvidenceRecorder {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final DiagnosisCaseService diagnosisCaseService;
    private final DiagnosisStepService diagnosisStepService;
    private final EvidenceChainService evidenceChainService;

    public FaultDiagnosisEvidenceRecorder(DiagnosisCaseService diagnosisCaseService,
                                         DiagnosisStepService diagnosisStepService,
                                         EvidenceChainService evidenceChainService) {
        this.diagnosisCaseService = diagnosisCaseService;
        this.diagnosisStepService = diagnosisStepService;
        this.evidenceChainService = evidenceChainService;
    }

    public EvidenceSession start(DiagnosisCommand command) {
        EvidenceSession session = new EvidenceSession();
        try {
            DiagnosisCaseEntity diagnosisCase = diagnosisCaseService.create(new DiagnosisCaseCreateCommand(
                command.context().sessionId(), command.context().agentId(), command.context().userId(), command.deviceName(),
                "DETERMINISTIC_FAULT_DIAGNOSIS", command.startTime(), command.endTime()));
            diagnosisCaseService.markRunning(diagnosisCase.getId());
            session.caseId = diagnosisCase.getId();
        } catch (RuntimeException e) {
            session.markFailure();
        }
        return session;
    }

    public EvidenceReference recordTelemetry(EvidenceSession session, DiagnosisCommand command, TelemetryQueryResult telemetry) {
        Map<String, Object> input = baseInput(command);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("assetCode", telemetry.assetCode());
        summary.put("faultCodeCount", safeSize(telemetry.faultCodes()));
        summary.put("alarmCodeCount", safeSize(telemetry.alarmCodes()));
        summary.put("unknownCodeCount", safeSize(telemetry.unknownCodes()));
        summary.put("dataSufficient", telemetry.quality() != null && telemetry.quality().sufficient());
        summary.put("fallbackToLatestData", telemetry.fallbackToLatestData());
        summary.put("latestObservedAt", telemetry.latestObservedAt() == null ? null
            : String.valueOf(telemetry.latestObservedAt()));
        if (telemetry.fallbackToLatestData()) {
            summary.put("analysisStartTime", String.valueOf(telemetry.startTime()));
            summary.put("analysisEndTime", String.valueOf(telemetry.endTime()));
        }
        if (!telemetry.codeNormalizationNotes().isEmpty()) {
            summary.put("codeNormalizationNotes", telemetry.codeNormalizationNotes());
        }
        int sourceCount = telemetry.quality() == null ? 0 : telemetry.quality().validRecordCount();
        return session.record(DiagnosisStepType.QUERY_TELEMETRY, EvidenceType.TELEMETRY, input, summary, sourceCount,
            rawSourceDigest(telemetry.sourceDigest()),
            BigDecimal.valueOf(telemetry.quality() == null ? 0D : telemetry.quality().completeness()),
            "遥测记录", telemetryDisplaySummary(command, telemetry), true);
    }

    /**
     * 服务端根据实际遥测结果确定性生成用户可读的证据摘要，不依赖模型改写。
     */
    private static String telemetryDisplaySummary(DiagnosisCommand command, TelemetryQueryResult telemetry) {
        StringBuilder out = new StringBuilder(command.deviceName());
        out.append("，").append(TIME_FORMATTER.format(telemetry.startTime()))
            .append("—").append(TIME_FORMATTER.format(telemetry.endTime()));
        int validCount = telemetry.quality() == null ? 0 : telemetry.quality().validRecordCount();
        out.append("，共").append(validCount).append("条有效记录");
        Set<String> observed = new LinkedHashSet<>(telemetry.faultCodes());
        observed.addAll(telemetry.alarmCodes());
        if (observed.isEmpty()) {
            out.append("，未出现故障码或报警码");
        } else {
            out.append("，出现 ").append(String.join("、", observed));
        }
        if (telemetry.fallbackToLatestData()) {
            out.append("；请求窗口无数据，使用最近可用数据");
        }
        return out.toString();
    }

    /**
     * 证据链按裸 64 位 SHA-256 存储和校验；遥测结果摘要带有算法前缀，入库前剥离。
     */
    private static String rawSourceDigest(String digest) {
        if (digest == null) {
            return null;
        }
        return digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
    }

    public EvidenceReference recordKnowledge(EvidenceSession session, String faultCode, List<Long> knowledgeBaseIds,
                                             FaultKnowledgeResult result) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("faultCode", faultCode);
        input.put("knowledgeBaseIds", knowledgeBaseIds);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", result == null ? "FAILED" : result.status().name());
        summary.put("evidenceCount", result == null || result.evidence() == null ? 0 : result.evidence().size());
        return session.record(DiagnosisStepType.LOOKUP_FAULT_CODE, EvidenceType.KNOWLEDGE, input, summary, 0, null,
            null, "手册资料", knowledgeDisplaySummary(faultCode, result), true);
    }

    /**
     * 知识证据的用户摘要：命中时给出手册条目与代码类型，未命中或失败时如实说明。
     */
    private static String knowledgeDisplaySummary(String faultCode, FaultKnowledgeResult result) {
        String term = FaultCodeType.classify(faultCode).term();
        String code = faultCode == null ? "未知代码" : faultCode;
        if (result == null || result.status() == FaultKnowledgeResult.Status.FAILED) {
            return code + " 的知识查询失败";
        }
        if (result.status() == FaultKnowledgeResult.Status.NOT_FOUND || result.evidence().isEmpty()) {
            return "未找到 " + code + "（" + term + "）的知识条目";
        }
        FaultKnowledgeEvidence first = result.evidence().get(0);
        String document = first.sourceDocument() == null || first.sourceDocument().isBlank()
            ? String.valueOf(first.documentId()) : first.sourceDocument();
        return "《" + document + "》" + code + " 条目，代码类型为" + term;
    }

    public EvidenceReference recordRules(EvidenceSession session, DiagnosisDecision decision, boolean fallbackToLatestData) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", decision.status().name());
        summary.put("observationCount", decision.observations().size());
        return session.record(DiagnosisStepType.APPLY_DIAGNOSIS_RULES, EvidenceType.RULE_RESULT, Map.of(), summary, 0, null,
            null, "判断规则", rulesDisplaySummary(fallbackToLatestData), true);
    }

    private static String rulesDisplaySummary(boolean fallbackToLatestData) {
        StringBuilder out = new StringBuilder("A 类代码归入报警，F 类代码归入故障，未知格式不升级为故障；报警不升级为故障结论");
        if (fallbackToLatestData) {
            out.append("；历史回退数据不能表示当前状态");
        }
        return out.toString();
    }

    public void recordResult(EvidenceSession session, DiagnosisResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", result.status().name());
        summary.put("partial", result.partial());
        summary.put("candidateFaultCount", result.candidateFaults().size());
        // 结果组装属于内部审计步骤，不进入普通用户回答。
        session.record(DiagnosisStepType.ASSEMBLE_RESULT, EvidenceType.RULE_RESULT, Map.of(), summary, 0, null,
            null, "结果记录", "诊断结果组装完成", false);
    }

    public void complete(EvidenceSession session, DiagnosisResult result) {
        if (session.caseId == null) {
            return;
        }
        try {
            Map<String, Object> caseSummary = new LinkedHashMap<>();
            caseSummary.put("status", result.status().name());
            caseSummary.put("partial", result.partial() || session.partial);
            caseSummary.put("candidateFaultCount", result.candidateFaults().size());
            caseSummary.put("evidenceCount", session.references().size());
            if (session.partial) {
                diagnosisCaseService.markPartial(session.caseId, caseSummary, "部分证据步骤记录失败");
            } else {
                diagnosisCaseService.markSucceeded(session.caseId, caseSummary);
            }
        } catch (RuntimeException e) {
            session.markFailure();
        }
    }

    /** 核心遥测步骤失败时仅尝试结束案例状态，原始异常仍由编排器向上抛出。 */
    public void fail(EvidenceSession session, Throwable error) {
        if (session.caseId == null) {
            return;
        }
        try {
            diagnosisCaseService.markFailed(session.caseId, error == null ? null : error.getMessage());
        } catch (RuntimeException ignored) {
            session.markFailure();
        }
    }

    private static Map<String, Object> baseInput(DiagnosisCommand command) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("deviceName", command.deviceName());
        input.put("inverterName", command.inverterName());
        input.put("startTime", command.startTime());
        input.put("endTime", command.endTime());
        return input;
    }

    private static int safeSize(List<?> values) {
        return values == null ? 0 : values.size();
    }

    /** 单次诊断的可变证据记录上下文，仅由编排线程顺序使用。 */
    public class EvidenceSession {
        private Long caseId;
        private int stepNo;
        private boolean partial;
        private final List<EvidenceReference> references = new ArrayList<>();
        private final List<String> limitations = new ArrayList<>();

        private EvidenceReference record(DiagnosisStepType stepType, EvidenceType evidenceType, Object input,
                                         Object summary, Integer sourceCount, String sourceDigest,
                                         BigDecimal qualityScore, String title, String displaySummary,
                                         boolean userVisible) {
            if (caseId == null) {
                return null;
            }
            DiagnosisStepEntity step = null;
            try {
                step = diagnosisStepService.start(new DiagnosisStepStartCommand(caseId, ++stepNo, stepType, input));
                EvidenceAppendResult appended = evidenceChainService.append(new EvidenceAppendCommand(caseId, step.getId(),
                    evidenceType, "ruoyi-fault", stepType.name(), input, summary, sourceCount, sourceDigest, qualityScore));
                diagnosisStepService.succeed(step.getId(), summary);
                EvidenceReference reference = new EvidenceReference(appended.evidenceId(), appended.evidenceCode(),
                    evidenceType, title, displaySummary, userVisible);
                references.add(reference);
                return reference;
            } catch (RuntimeException e) {
                if (step != null && step.getId() != null) {
                    try {
                        diagnosisStepService.fail(step.getId(), e);
                    } catch (RuntimeException ignored) {
                        // 已经进入降级路径，不能让证据状态更新再次影响核心诊断。
                    }
                }
                markFailure();
                return null;
            }
        }

        private void markFailure() {
            partial = true;
            if (!limitations.contains("部分证据记录失败，结果未包含虚构证据编号")) {
                limitations.add("部分证据记录失败，结果未包含虚构证据编号");
            }
        }

        public boolean partial() {
            return partial;
        }

        public List<EvidenceReference> references() {
            return List.copyOf(references);
        }

        public List<String> limitations() {
            return List.copyOf(limitations);
        }
    }
}
