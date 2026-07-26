package org.ruoyi.fault.domain.result;

import org.ruoyi.fault.domain.enums.DiagnosisStatus;

import java.util.List;

/** 规则引擎的纯逻辑输出。 */
public record DiagnosisDecision(
    DiagnosisStatus status,
    List<DiagnosisObservation> observations,
    List<String> recommendations,
    List<String> limitations
) {
    public DiagnosisDecision {
        observations = observations == null ? List.of() : List.copyOf(observations);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
