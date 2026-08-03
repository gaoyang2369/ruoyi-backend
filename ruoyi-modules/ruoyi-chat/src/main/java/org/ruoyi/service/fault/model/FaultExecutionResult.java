package org.ruoyi.service.fault.model;

import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 已执行的确定性结果；不保存原始遥测。 */
public record FaultExecutionResult(FaultRequestPlan plan, DiagnosisResult diagnosisResult,
                                   Map<String, FaultKnowledgeResult> explicitKnowledgeResults,
                                   List<String> limitations) {
    public FaultExecutionResult {
        explicitKnowledgeResults = explicitKnowledgeResults == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(explicitKnowledgeResults));
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    /** 持久化证据编号，按诊断执行顺序返回有序列表，不依赖 Set 迭代顺序。 */
    public List<String> allowedEvidenceCodes() {
        if (diagnosisResult == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (EvidenceReference reference : diagnosisResult.evidenceIndex()) {
            if (reference != null && reference.evidenceCode() != null && !reference.evidenceCode().isBlank()) result.add(reference.evidenceCode());
        }
        return List.copyOf(result);
    }

    /** 按诊断执行顺序返回用户可见证据；内部审计步骤不进入普通回答。 */
    public List<EvidenceReference> userVisibleEvidence() {
        if (diagnosisResult == null) return List.of();
        List<EvidenceReference> result = new ArrayList<>();
        for (EvidenceReference reference : diagnosisResult.evidenceIndex()) {
            if (reference != null && reference.userVisible() && reference.evidenceCode() != null
                && !reference.evidenceCode().isBlank()) result.add(reference);
        }
        return List.copyOf(result);
    }

    /** 本次诊断结果中确实出现的 F 类故障码，不包含报警码，也不包含用户仅查询的码。 */
    public Set<String> observedFaultCodes() {
        if (diagnosisResult == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        diagnosisResult.candidateFaults().forEach(item -> {
            if (item.codeType() == FaultCodeType.FAULT) addNormalized(result, item.faultCode());
        });
        diagnosisResult.faultCodes().forEach(code -> {
            if (FaultCodeType.isFault(code)) addNormalized(result, code);
        });
        return Set.copyOf(result);
    }

    /** 本次诊断结果中确实出现的 A 类报警码。 */
    public Set<String> observedAlarmCodes() {
        if (diagnosisResult == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        diagnosisResult.candidateFaults().forEach(item -> {
            if (item.codeType() == FaultCodeType.ALARM) addNormalized(result, item.faultCode());
        });
        diagnosisResult.alarmCodes().forEach(code -> {
            if (FaultCodeType.isAlarm(code)) addNormalized(result, code);
        });
        return Set.copyOf(result);
    }

    /** 用户明确查询、但没有在本次遥测中确认出现的故障/报警码。 */
    public Set<String> queriedOnlyCodes() {
        Set<String> observed = new LinkedHashSet<>(observedFaultCodes());
        observed.addAll(observedAlarmCodes());
        Set<String> result = new LinkedHashSet<>();
        if (plan != null) plan.faultCodes().forEach(code -> {
            String normalized = normalize(code);
            if (normalized != null && !observed.contains(normalized)) result.add(normalized);
        });
        return Set.copyOf(result);
    }

    public List<FaultKnowledgeEvidence> knowledgeSources() {
        return knowledgeSourcesWithFaultCode().stream().map(KnowledgeSource::evidence).toList();
    }

    /** 已按持久化来源身份去重，保留故障码归属供确定性附录展示。 */
    public List<KnowledgeSource> knowledgeSourcesWithFaultCode() {
        Map<String, KnowledgeSource> sources = new LinkedHashMap<>();
        if (diagnosisResult != null) {
            for (CandidateFault candidate : diagnosisResult.candidateFaults()) add(sources, candidate.faultCode(), candidate.knowledgeEvidence());
        }
        for (Map.Entry<String, FaultKnowledgeResult> entry : explicitKnowledgeResults.entrySet()) if (entry.getValue() != null) add(sources, entry.getKey(), entry.getValue().evidence());
        return List.copyOf(new ArrayList<>(sources.values()));
    }

    private static void add(Map<String, KnowledgeSource> target, String faultCode, List<FaultKnowledgeEvidence> items) {
        if (items == null) return;
        for (FaultKnowledgeEvidence item : items) if (item != null) {
            target.putIfAbsent(String.valueOf(item.knowledgeBaseId()) + "|" + item.documentId() + "|" + item.fragmentId(), new KnowledgeSource(normalize(faultCode), item));
        }
    }

    private static void addNormalized(Set<String> target, String code) {
        String normalized = normalize(code);
        if (normalized != null) target.add(normalized);
    }

    private static String normalize(String code) {
        try {
            return FaultKnowledgeQuery.normalizeFaultCode(code);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public record KnowledgeSource(String faultCode, FaultKnowledgeEvidence evidence) { }
}
