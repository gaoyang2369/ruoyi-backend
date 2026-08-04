package org.ruoyi.fault.evidence.enums;

/** 可独立引用的证据类型。 */
public enum EvidenceType {
    TELEMETRY("遥测记录"),
    DATA_QUALITY("数据质量"),
    FAULT_CODE("故障码"),
    ALARM_CODE("报警码"),
    KNOWLEDGE("手册资料"),
    RULE_RESULT("判断规则");

    private final String displayName;

    EvidenceType(String displayName) {
        this.displayName = displayName;
    }

    /** 面向用户的中文类型名；内部标识仍使用 {@link #name()}。 */
    public String getDisplayName() {
        return displayName;
    }
}
