package org.ruoyi.fault.diagnosis;

import org.ruoyi.fault.application.DiagnosisCommandValidator;
import org.ruoyi.fault.application.DiagnosisResultAssembler;
import org.ruoyi.fault.application.FaultDiagnosisEvidenceRecorder;
import org.ruoyi.fault.application.FaultRuleEngine;
import org.ruoyi.fault.application.KnowledgeLookupAggregation;
import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.domain.enums.ObservationType;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisDecision;
import org.ruoyi.fault.domain.result.DiagnosisObservation;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.knowledge.FaultKnowledgePort;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.service.TelemetryQueryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 诊断的线性、确定性编排入口。串行查询显式故障码和报警码的知识，不依赖聊天、LLM 或 SQL 输入。
 */
@Service
public class FaultDiagnosisOrchestrator {

    private final DiagnosisCommandValidator validator;
    private final TelemetryQueryService telemetryQueryService;
    private final FaultKnowledgePort faultKnowledgePort;
    private final FaultRuleEngine faultRuleEngine;
    private final DiagnosisResultAssembler resultAssembler;
    private final FaultDiagnosisEvidenceRecorder evidenceRecorder;

    public FaultDiagnosisOrchestrator(DiagnosisCommandValidator validator, TelemetryQueryService telemetryQueryService,
                                      FaultKnowledgePort faultKnowledgePort, FaultRuleEngine faultRuleEngine,
                                      DiagnosisResultAssembler resultAssembler,
                                      FaultDiagnosisEvidenceRecorder evidenceRecorder) {
        this.validator = validator;
        this.telemetryQueryService = telemetryQueryService;
        this.faultKnowledgePort = faultKnowledgePort;
        this.faultRuleEngine = faultRuleEngine;
        this.resultAssembler = resultAssembler;
        this.evidenceRecorder = evidenceRecorder;
    }

    public DiagnosisResult diagnose(DiagnosisCommand command) {
        DiagnosisCommand normalized = validator.validateAndNormalize(command);
        FaultDiagnosisEvidenceRecorder.EvidenceSession evidence = evidenceRecorder.start(normalized);
        TelemetryQueryResult telemetry;
        try {
            telemetry = telemetryQueryService.queryTelemetry(normalized.deviceName(), normalized.inverterName(),
                normalized.startTime(), normalized.endTime());
        } catch (RuntimeException e) {
            evidenceRecorder.fail(evidence, e);
            throw e;
        }
        return diagnoseWithTelemetry(normalized, telemetry, evidence);
    }

    /**
     * 使用调用方已持有的遥测快照执行诊断，保证报告与诊断共用同一份数据和来源摘要。
     * 调用方拥有数据，因此不存在遥测查询失败分支。
     */
    public DiagnosisResult diagnose(DiagnosisCommand command, TelemetryQueryResult telemetry) {
        DiagnosisCommand normalized = validator.validateAndNormalize(command);
        FaultDiagnosisEvidenceRecorder.EvidenceSession evidence = evidenceRecorder.start(normalized);
        return diagnoseWithTelemetry(normalized, telemetry, evidence);
    }

    private DiagnosisResult diagnoseWithTelemetry(DiagnosisCommand normalized, TelemetryQueryResult telemetry,
                                                  FaultDiagnosisEvidenceRecorder.EvidenceSession evidence) {
        evidenceRecorder.recordTelemetry(evidence, normalized, telemetry);

        KnowledgeLookupAggregation knowledge = lookupKnowledge(telemetry.faultCodes(), telemetry.alarmCodes(),
            normalized.knowledgeBaseIds(), evidence);
        DiagnosisDecision decision = faultRuleEngine.evaluate(telemetry, knowledge.candidateFaults());
        evidenceRecorder.recordRules(evidence, decision, telemetry.fallbackToLatestData());
        DiagnosisResult result = resultAssembler.assemble(normalized, telemetry, knowledge, decision, evidence.references(),
            evidence.partial(), evidence.limitations());
        evidenceRecorder.recordResult(evidence, result);
        evidenceRecorder.complete(evidence, result);
        return result.withEvidenceIndex(evidence.references(), evidence.partial(),
            mergeLimitations(result.limitations(), evidence.limitations()));
    }

    /**
     * 知识查询输入覆盖本次观测到的故障码和报警码，并保留代码类型。
     * 故障码在前、报警码在后，保持结论形成顺序。
     */
    private KnowledgeLookupAggregation lookupKnowledge(List<String> telemetryFaultCodes, List<String> telemetryAlarmCodes,
                                                         List<Long> knowledgeBaseIds,
                                                         FaultDiagnosisEvidenceRecorder.EvidenceSession evidence) {
        Map<String, FaultCodeType> uniqueCodes = new LinkedHashMap<>();
        collectKnowledgeCodes(uniqueCodes, telemetryFaultCodes, FaultCodeType.FAULT);
        collectKnowledgeCodes(uniqueCodes, telemetryAlarmCodes, FaultCodeType.ALARM);
        List<CandidateFault> candidates = new ArrayList<>();
        List<DiagnosisObservation> observations = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        if (uniqueCodes.isEmpty()) {
            return new KnowledgeLookupAggregation(candidates, observations, limitations);
        }
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            for (Map.Entry<String, FaultCodeType> entry : uniqueCodes.entrySet()) {
                candidates.add(new CandidateFault(entry.getKey(), entry.getValue(), KnowledgeLookupStatus.SKIPPED,
                    List.of(), List.of()));
            }
            limitations.add("Agent 未绑定可用知识库，未执行故障/报警知识查询");
            return new KnowledgeLookupAggregation(candidates, observations, limitations);
        }
        for (Map.Entry<String, FaultCodeType> entry : uniqueCodes.entrySet()) {
            String rawCode = entry.getKey();
            FaultCodeType codeType = entry.getValue();
            FaultKnowledgeQuery query;
            try {
                query = new FaultKnowledgeQuery(rawCode, knowledgeBaseIds);
            } catch (RuntimeException e) {
                candidates.add(failedCandidate(rawCode, codeType, knowledgeBaseIds, evidence, observations, limitations));
                continue;
            }
            FaultKnowledgeResult result;
            try {
                result = faultKnowledgePort.query(query);
                if (result == null) {
                    result = FaultKnowledgeResult.failed(query);
                }
            } catch (RuntimeException e) {
                result = FaultKnowledgeResult.failed(query);
            }
            EvidenceReference reference = evidenceRecorder.recordKnowledge(evidence, query.faultCode(), knowledgeBaseIds, result);
            List<String> evidenceCodes = reference == null ? List.of() : List.of(reference.evidenceCode());
            candidates.add(toCandidate(query.faultCode(), codeType, result, evidenceCodes, observations, limitations));
        }
        return new KnowledgeLookupAggregation(candidates, observations, limitations);
    }

    private void collectKnowledgeCodes(Map<String, FaultCodeType> uniqueCodes, List<String> telemetryCodes,
                                       FaultCodeType defaultType) {
        if (telemetryCodes == null) {
            return;
        }
        for (String value : telemetryCodes) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            // 遥测库约定裸值 0 为无代码；即使上游绕过分析器，也绝不能检索知识库。
            if ("0".equals(trimmed)) {
                continue;
            }
            String code;
            try {
                code = FaultKnowledgeQuery.normalizeFaultCode(trimmed);
            } catch (RuntimeException ignored) {
                // 非法遥测码仍作为显式观测保留，随后会得到单码 FAILED 候选。
                code = trimmed;
            }
            uniqueCodes.putIfAbsent(code, defaultType);
        }
    }

    private CandidateFault failedCandidate(String rawCode, FaultCodeType codeType, List<Long> knowledgeBaseIds,
                                           FaultDiagnosisEvidenceRecorder.EvidenceSession evidence,
                                           List<DiagnosisObservation> observations, List<String> limitations) {
        FaultKnowledgeResult failure = null;
        EvidenceReference reference = evidenceRecorder.recordKnowledge(evidence, rawCode, knowledgeBaseIds, failure);
        List<String> codes = reference == null ? List.of() : List.of(reference.evidenceCode());
        observations.add(new DiagnosisObservation("KNOWLEDGE_FAILURE:" + rawCode, ObservationType.KNOWLEDGE_FAILURE,
            codeType.term() + "知识查询失败: " + rawCode, List.of(rawCode), codes));
        limitations.add(codeType.term() + "码 " + rawCode + " 的知识查询失败（故障知识查询暂不可用，请稍后重试），已保留显式观测");
        return new CandidateFault(rawCode, codeType, KnowledgeLookupStatus.FAILED, List.of(), codes);
    }

    private CandidateFault toCandidate(String faultCode, FaultCodeType codeType, FaultKnowledgeResult result,
                                       List<String> evidenceCodes, List<DiagnosisObservation> observations,
                                       List<String> limitations) {
        String term = codeType.term();
        return switch (result.status()) {
            case MATCHED -> {
                observations.add(new DiagnosisObservation("KNOWLEDGE_MATCH:" + faultCode, ObservationType.KNOWLEDGE_MATCH,
                    term + "码已匹配知识依据: " + faultCode, List.of(faultCode), evidenceCodes));
                yield new CandidateFault(faultCode, codeType, KnowledgeLookupStatus.MATCHED, result.evidence(), evidenceCodes);
            }
            case NOT_FOUND -> {
                observations.add(new DiagnosisObservation("KNOWLEDGE_MISSING:" + faultCode, ObservationType.KNOWLEDGE_MISSING,
                    "未找到" + term + "码知识依据: " + faultCode, List.of(faultCode), evidenceCodes));
                limitations.add(term + "码 " + faultCode + " 未找到匹配的知识依据");
                yield new CandidateFault(faultCode, codeType, KnowledgeLookupStatus.NOT_FOUND, List.of(), evidenceCodes);
            }
            case FAILED -> {
                observations.add(new DiagnosisObservation("KNOWLEDGE_FAILURE:" + faultCode, ObservationType.KNOWLEDGE_FAILURE,
                    term + "知识查询失败: " + faultCode, List.of(faultCode), evidenceCodes));
                limitations.add(term + "码 " + faultCode + " 的知识查询失败（故障知识查询暂不可用，请稍后重试），已保留显式观测");
                yield new CandidateFault(faultCode, codeType, KnowledgeLookupStatus.FAILED, List.of(), evidenceCodes);
            }
        };
    }

    private static List<String> mergeLimitations(List<String> first, List<String> second) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (first != null) {
            result.addAll(first);
        }
        if (second != null) {
            result.addAll(second);
        }
        return List.copyOf(result);
    }
}
