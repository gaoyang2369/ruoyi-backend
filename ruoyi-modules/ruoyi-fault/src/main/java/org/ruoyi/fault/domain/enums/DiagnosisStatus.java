package org.ruoyi.fault.domain.enums;

/** 诊断状态，声明顺序即确定性规则的优先级。 */
public enum DiagnosisStatus {
    DATA_INSUFFICIENT,
    FAULT_DETECTED,
    WARNING_DETECTED,
    NO_EXPLICIT_FAULT
}
