package org.ruoyi.fault.application;

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
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对提交五已存在证据服务的薄适配层。证据记录失败不伪造编号，且不阻断确定性核心诊断。
 */
@Component
public class FaultDiagnosisEvidenceRecorder {

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

    public void recordTelemetry(EvidenceSession session, DiagnosisCommand command, TelemetryQueryResult telemetry) {
        Map<String, Object> input = baseInput(command);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("assetCode", telemetry.assetCode());
        summary.put("faultCodeCount", safeSize(telemetry.faultCodes()));
        summary.put("alarmCodeCount", safeSize(telemetry.alarmCodes()));
        summary.put("dataSufficient", telemetry.quality() != null && telemetry.quality().sufficient());
        int sourceCount = telemetry.quality() == null ? 0 : telemetry.quality().validRecordCount();
        session.record(DiagnosisStepType.QUERY_TELEMETRY, EvidenceType.TELEMETRY, input, summary, sourceCount,
            telemetry.sourceDigest(), BigDecimal.valueOf(telemetry.quality() == null ? 0D : telemetry.quality().completeness()));
    }

    public EvidenceReference recordKnowledge(EvidenceSession session, String faultCode, List<Long> knowledgeBaseIds,
                                             FaultKnowledgeResult result) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("faultCode", faultCode);
        input.put("knowledgeBaseIds", knowledgeBaseIds);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", result == null ? "FAILED" : result.status().name());
        summary.put("evidenceCount", result == null || result.evidence() == null ? 0 : result.evidence().size());
        return session.record(DiagnosisStepType.LOOKUP_FAULT_CODE, EvidenceType.KNOWLEDGE, input, summary, 0, null, null);
    }

    public void recordRules(EvidenceSession session, DiagnosisDecision decision) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", decision.status().name());
        summary.put("observationCount", decision.observations().size());
        session.record(DiagnosisStepType.APPLY_DIAGNOSIS_RULES, EvidenceType.RULE_RESULT, Map.of(), summary, 0, null, null);
    }

    public void recordResult(EvidenceSession session, DiagnosisResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", result.status().name());
        summary.put("partial", result.partial());
        summary.put("candidateFaultCount", result.candidateFaults().size());
        session.record(DiagnosisStepType.ASSEMBLE_RESULT, EvidenceType.RULE_RESULT, Map.of(), summary, 0, null, null);
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
                                         Object summary, Integer sourceCount, String sourceDigest, BigDecimal qualityScore) {
            if (caseId == null) {
                return null;
            }
            DiagnosisStepEntity step = null;
            try {
                step = diagnosisStepService.start(new DiagnosisStepStartCommand(caseId, ++stepNo, stepType, input));
                EvidenceAppendResult appended = evidenceChainService.append(new EvidenceAppendCommand(caseId, step.getId(),
                    evidenceType, "ruoyi-fault", stepType.name(), input, summary, sourceCount, sourceDigest, qualityScore));
                diagnosisStepService.succeed(step.getId(), summary);
                EvidenceReference reference = new EvidenceReference(appended.evidenceId(), appended.evidenceCode());
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
