package org.ruoyi.fault.report;

import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;

import java.time.LocalDateTime;

/**
 * 设备运行与状态报告的唯一结果对象。
 * <p>
 * 报告是对既有遥测快照与确定性诊断的聚合视图：所有事实都来自 {@code telemetry}
 * 与 {@code diagnosis}，本对象只额外承载报告编号、生成时间、健康状态与确定性摘要。
 * 渲染层（Markdown、HTML、PDF）只能消费本对象，不得重新计算事实。
 *
 * @param reportCode 报告编号，形如 RP-雪花ID，用于展示与溯源
 * @param deviceName 设备名称
 * @param inverterName 逆变器名称
 * @param requestedStartTime 用户请求窗口起点（包含）
 * @param requestedEndTime 用户请求窗口终点（不包含）
 * @param generatedAt 报告生成时间，使用故障诊断配置时区
 * @param healthStatus 离散设备健康状态
 * @param summary 服务端确定性生成的运行结论段落，不依赖任何大模型
 * @param telemetry 报告与诊断共用的遥测快照
 * @param diagnosis 基于同一遥测快照的确定性诊断结果
 */
public record OperationReportResult(
    String reportCode,
    String deviceName,
    String inverterName,
    LocalDateTime requestedStartTime,
    LocalDateTime requestedEndTime,
    LocalDateTime generatedAt,
    ReportHealthStatus healthStatus,
    String summary,
    TelemetryQueryResult telemetry,
    DiagnosisResult diagnosis
) {
}
