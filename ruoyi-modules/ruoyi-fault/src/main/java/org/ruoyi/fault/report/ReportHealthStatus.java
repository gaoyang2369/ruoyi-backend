package org.ruoyi.fault.report;

import org.ruoyi.fault.domain.enums.DiagnosisStatus;

/**
 * 运行报告的离散设备健康状态。
 * <p>
 * 当前诊断仅依据故障码与报警码，不提供未经模型验证的健康评分；
 * 状态与确定性诊断结论一一对应，可被第三方系统直接消费。
 */
public enum ReportHealthStatus {

    /** 数据充分，未发现故障码或报警码。 */
    NORMAL("正常"),

    /** 存在报警码，但未发现明确故障码。 */
    ATTENTION("关注"),

    /** 存在已识别的故障码。 */
    FAULT("故障"),

    /** 无数据或数据质量不足，无法判断。 */
    UNKNOWN("未知");

    private final String displayName;

    ReportHealthStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 由确定性诊断状态直接映射；规则引擎的状态级联已包含数据质量判断，
     * 此处不再叠加其他条件。
     */
    public static ReportHealthStatus fromDiagnosisStatus(DiagnosisStatus status) {
        if (status == null) {
            return UNKNOWN;
        }
        return switch (status) {
            case DATA_INSUFFICIENT -> UNKNOWN;
            case FAULT_DETECTED -> FAULT;
            case WARNING_DETECTED -> ATTENTION;
            case NO_EXPLICIT_FAULT -> NORMAL;
        };
    }

}
