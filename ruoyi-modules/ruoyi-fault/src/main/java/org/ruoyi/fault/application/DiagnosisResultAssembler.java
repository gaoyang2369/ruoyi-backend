package org.ruoyi.fault.application;

import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisDecision;
import org.ruoyi.fault.domain.result.DiagnosisObservation;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 将命令、遥测、知识查询和规则判断合成为稳定的不可变结果。 */
@Component
public class DiagnosisResultAssembler {

    public DiagnosisResult assemble(DiagnosisCommand command, TelemetryQueryResult telemetry,
                                    KnowledgeLookupAggregation knowledge, DiagnosisDecision decision) {
        return assemble(command, telemetry, knowledge, decision, List.of(), false, List.of());
    }

    public DiagnosisResult assemble(DiagnosisCommand command, TelemetryQueryResult telemetry,
                                    KnowledgeLookupAggregation knowledge, DiagnosisDecision decision,
                                    List<EvidenceReference> evidenceIndex, boolean evidencePartial,
                                    List<String> evidenceLimitations) {
        List<CandidateFault> candidates = knowledge.candidateFaults();
        List<DiagnosisObservation> observations = new ArrayList<>(decision.observations());
        observations.addAll(knowledge.observations());
        List<String> recommendations = distinct(decision.recommendations());
        List<String> limitations = new ArrayList<>(decision.limitations());
        limitations.addAll(knowledge.limitations());
        limitations.addAll(evidenceLimitations == null ? List.of() : evidenceLimitations);

        boolean partial = evidencePartial || candidates.stream()
            .anyMatch(candidate -> candidate.knowledgeStatus() == KnowledgeLookupStatus.FAILED);
        return new DiagnosisResult(command.context().requestId(), decision.status(), partial, command.deviceName(),
            command.inverterName(), command.startTime(), command.endTime(), command.symptom(), telemetry.quality(),
            telemetry.statistics(), telemetry.faultCodes(), telemetry.alarmCodes(), observations, candidates,
            recommendations, distinct(limitations), evidenceIndex);
    }

    private static List<String> distinct(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null) {
                    result.add(value);
                }
            }
        }
        return List.copyOf(result);
    }
}
