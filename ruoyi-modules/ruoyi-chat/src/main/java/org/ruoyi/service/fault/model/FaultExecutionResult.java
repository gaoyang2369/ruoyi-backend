package org.ruoyi.service.fault.model;

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

    public Set<String> allowedEvidenceCodes() {
        if (diagnosisResult == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (EvidenceReference reference : diagnosisResult.evidenceIndex()) {
            if (reference != null && reference.evidenceCode() != null && !reference.evidenceCode().isBlank()) result.add(reference.evidenceCode());
        }
        return Set.copyOf(result);
    }

    /** 本次诊断结果中确实出现的故障码，不包含用户仅查询的码。 */
    public Set<String> observedFaultCodes() {
        if (diagnosisResult == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        diagnosisResult.candidateFaults().forEach(item -> addNormalized(result, item.faultCode()));
        diagnosisResult.faultCodes().forEach(code -> addNormalized(result, code));
        return Set.copyOf(result);
    }

    /** 用户明确查询、但没有在本次遥测中确认出现的故障码。 */
    public Set<String> queriedOnlyFaultCodes() {
        Set<String> observed = observedFaultCodes();
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
