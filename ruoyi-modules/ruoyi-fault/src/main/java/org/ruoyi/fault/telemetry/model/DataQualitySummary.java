package org.ruoyi.fault.telemetry.model;

/**
 * 运行数据质量摘要。
 *
 * @param rawRecordCount create_time 粗筛返回的行数，包含缓冲区行
 * @param validRecordCount 精确时间过滤并去重后的有效行数
 * @param duplicateCount 被确定性去重规则折叠的行数
 * @param invalidTimeCount timestamp 与 date + time 均无法解析的行数
 * @param gapCount 按标称采样周期推算的缺失采样点数
 * @param completeness 有效行数除以理论采样数，最大为 1
 * @param sufficient 完整度是否达到配置阈值
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
