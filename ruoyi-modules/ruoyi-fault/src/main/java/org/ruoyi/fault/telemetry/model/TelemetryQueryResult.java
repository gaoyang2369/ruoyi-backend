package org.ruoyi.fault.telemetry.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 固定遥测查询的聚合结果，不包含原始时序行。
 *
 * @param assetCode 已通过权限校验的资产编码
 * @param startTime 精确窗口起点（包含）
 * @param endTime 精确窗口终点（不包含）
 * @param quality 数据质量摘要
 * @param faultCodes 窗口内非空且去重后的故障码
 * @param alarmCodes 窗口内非空且去重后的报警码
 * @param statusEvents 状态、故障码或报警码发生变化时的关键事件
 * @param statistics 关键数值指标统计
 * @param sourceDigest 覆盖查询条件与后端证据行的 SHA-256 摘要
 */
public record TelemetryQueryResult(
    String assetCode,
    LocalDateTime startTime,
    LocalDateTime endTime,
    DataQualitySummary quality,
    List<String> faultCodes,
    List<String> alarmCodes,
    List<StatusEvent> statusEvents,
    TelemetryStatistics statistics,
    String sourceDigest
) {
}
