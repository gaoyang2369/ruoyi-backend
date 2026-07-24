package org.ruoyi.fault.telemetry.model;

/**
 * 提供给诊断编排器的关键数值统计量。
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
