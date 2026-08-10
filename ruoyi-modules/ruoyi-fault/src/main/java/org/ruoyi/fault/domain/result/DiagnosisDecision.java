package org.ruoyi.fault.domain.result;

import org.ruoyi.fault.domain.enums.DiagnosisStatus;

import java.util.List;

/** 规则引擎的纯逻辑输出。 */
public record DiagnosisDecision(
    DiagnosisStatus status,
    List<DiagnosisObservation> observations,
    List<String> recommendations,
    List<String> limitations,
    List<String> decisionRationale
) {
    public DiagnosisDecision {
        observations = observations == null ? List.of() : List.copyOf(observations);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        decisionRationale = decisionRationale == null ? List.of() : List.copyOf(decisionRationale);
    }

    /** 兼容既有规则实现与测试构造。 */
    public DiagnosisDecision(DiagnosisStatus status, List<DiagnosisObservation> observations,
                             List<String> recommendations, List<String> limitations) {
        this(status, observations, recommendations, limitations, List.of());
    }
}
