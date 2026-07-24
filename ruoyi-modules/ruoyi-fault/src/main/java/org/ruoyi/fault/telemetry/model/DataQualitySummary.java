package org.ruoyi.fault.telemetry.model;

/**
 * 运行数据质量摘要。
 */
public record DataQualitySummary(
    int rawRecordCount,
    int validRecordCount,
    int duplicateCount,
    int invalidTimeCount,
    int gapCount,
    double completeness,
    boolean sufficient
) {
}
