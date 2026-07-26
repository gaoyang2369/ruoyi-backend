package org.ruoyi.fault.evidence.model;

import org.ruoyi.fault.evidence.enums.DiagnosisStepType;

/** 启动诊断步骤的输入。 */
public record DiagnosisStepStartCommand(
    Long caseId,
    Integer stepNo,
    DiagnosisStepType stepType,
    Object input
) {
}
