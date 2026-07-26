package org.ruoyi.fault.application;

import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisObservation;

import java.util.List;

/** 串行故障知识查询的中间结果。 */
public record KnowledgeLookupAggregation(
    List<CandidateFault> candidateFaults,
    List<DiagnosisObservation> observations,
    List<String> limitations
) {
    public KnowledgeLookupAggregation {
        candidateFaults = candidateFaults == null ? List.of() : List.copyOf(candidateFaults);
        observations = observations == null ? List.of() : List.copyOf(observations);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
