package org.ruoyi.fault.domain.result;

import org.ruoyi.fault.domain.enums.ObservationType;

import java.util.List;

/** 一个只陈述观测事实的诊断观察，不推断未定义的阈值或根因。 */
public record DiagnosisObservation(
    String observationCode,
    ObservationType type,
    String message,
    List<String> relatedCodes,
    List<String> evidenceCodes
) {
    public DiagnosisObservation {
        relatedCodes = relatedCodes == null ? List.of() : List.copyOf(relatedCodes);
        evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
    }
}
