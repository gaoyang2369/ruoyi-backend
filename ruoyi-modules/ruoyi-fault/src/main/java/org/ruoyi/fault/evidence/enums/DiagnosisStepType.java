package org.ruoyi.fault.evidence.enums;

/** 第一阶段预留的诊断步骤类型。 */
public enum DiagnosisStepType {
    RESOLVE_INTENT,
    VALIDATE_REQUEST,
    QUERY_TELEMETRY,
    CHECK_DATA_QUALITY,
    LOOKUP_FAULT_CODE,
    APPLY_DIAGNOSIS_RULES,
    ASSEMBLE_RESULT,
    GENERATE_ANSWER
}
