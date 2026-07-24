package org.ruoyi.fault.telemetry.model;

/**
 * 提供给诊断编排器的关键数值统计量。
 * <p>
 * 某指标在窗口内没有有效值时，其最小值、最大值和平均值保持为 null，避免把缺失误判为 0。
 */
public record TelemetryStatistics(
    int sampleCount,
    Double minActualPower,
    Double maxActualPower,
    Double avgActualPower,
    Double minMotorTemp,
    Double maxMotorTemp,
    Double avgMotorTemp,
    Double minInverterTemp,
    Double maxInverterTemp,
    Double avgInverterTemp,
    Double maxInverterLoadRate,
    Double maxMotorLoadRate
) {
}
