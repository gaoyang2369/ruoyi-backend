package org.ruoyi.fault.report;

import cn.hutool.core.util.IdUtil;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.diagnosis.FaultDiagnosisOrchestrator;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetryReportSnapshot;
import org.ruoyi.fault.telemetry.service.TelemetryQueryService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 设备运行与状态报告的确定性编排入口。
 * <p>
 * 遥测只查询一次，诊断通过共享快照重载执行，保证报告与诊断基于同一份数据
 * 与同一个来源摘要。报告事实全部来自后端结构化结果，摘要段落由确定性模板
 * 生成，不依赖任何大模型。
 */
@Service
public class OperationReportOrchestrator {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final TelemetryQueryService telemetryQueryService;
    private final FaultDiagnosisOrchestrator faultDiagnosisOrchestrator;
    private final OperationReportAnalysisService operationReportAnalysisService;
    private final FaultDiagnosisProperties properties;

    public OperationReportOrchestrator(TelemetryQueryService telemetryQueryService,
                                       FaultDiagnosisOrchestrator faultDiagnosisOrchestrator,
                                       OperationReportAnalysisService operationReportAnalysisService,
                                       FaultDiagnosisProperties properties) {
        this.telemetryQueryService = telemetryQueryService;
        this.faultDiagnosisOrchestrator = faultDiagnosisOrchestrator;
        this.operationReportAnalysisService = operationReportAnalysisService;
        this.properties = properties;
    }

    public OperationReportResult generate(DiagnosisCommand command) {
        TelemetryReportSnapshot reportSnapshot = telemetryQueryService.queryReportTelemetry(
            command.deviceName(), command.inverterName(), command.startTime(), command.endTime());
        TelemetryQueryResult telemetry = reportSnapshot.telemetry();
        DiagnosisResult diagnosis = faultDiagnosisOrchestrator.diagnose(command, telemetry);
        ReportHealthStatus periodStatus = ReportHealthStatus.fromDiagnosisStatus(diagnosis.status());
        OperationReportResult report = OperationReportResult.fromSources(
            "RP-" + IdUtil.getSnowflakeNextId(),
            command.deviceName(),
            command.inverterName(),
            command.startTime(),
            command.endTime(),
            LocalDateTime.now(ZoneId.of(properties.getTimezone())),
            periodStatus,
            buildSummary(periodStatus, telemetry, diagnosis),
            telemetry,
            reportSnapshot.statistics(),
            reportSnapshot.series(),
            properties.getMetricUnits(),
            diagnosis);
        return report.withAnalysisFacts(operationReportAnalysisService.analyze(report.period(), report.metrics(),
            report.metricUnits(), report.events(), reportSnapshot.analysisSamples()));
    }

    /**
     * 运行结论段落：先说发生了什么，再给数据质量与诊断边界，全部取自结构化事实。
     */
    private OperationReportResult.Summary buildSummary(ReportHealthStatus periodStatus, TelemetryQueryResult telemetry,
                                                        DiagnosisResult diagnosis) {
        StringBuilder out = new StringBuilder();
        out.append("报告周期内设备状态：").append(periodStatus.getDisplayName()).append("。");
        // 摘要只点状态与代码清单；采样命中、首末时间等明细由报告第 3 节呈现，避免重复。
        switch (periodStatus) {
            case FAULT -> appendCodeSentence(out, "检测到故障码", diagnosis.faultCodes());
            case ATTENTION -> appendCodeSentence(out, "存在报警码", diagnosis.alarmCodes());
            // 只陈述未发现显式代码，不扩大为“设备完全健康”
            case NORMAL -> out.append("有效数据中未观测到显式故障码或报警码。");
            case UNKNOWN -> out.append("有效数据中未观测到显式故障码或报警码，但数据不足，无法确认整个周期不存在故障或报警。");
        }
        DataQualitySummary quality = telemetry.quality();
        if (quality != null) {
            out.append("数据完整率 ")
                .append(String.format(Locale.ROOT, "%.1f%%", quality.completeness() * 100))
                .append("（有效样本 ").append(quality.validRecordCount()).append(" 条）。");
        }
        if (telemetry.fallbackToLatestData()) {
            out.append("请求窗口无数据，已改用最近可用数据窗口，本结果为历史数据分析");
            if (telemetry.latestObservedAt() != null) {
                out.append("（最新数据时间 ").append(formatTime(telemetry.latestObservedAt())).append("）");
            }
            out.append("。");
        }
        if (diagnosis.partial()) {
            out.append("本次诊断存在降级，请结合限制说明谨慎使用。");
        }
        return new OperationReportResult.Summary(out.toString(), diagnosis.faultCodes(), diagnosis.alarmCodes(),
            !telemetry.fallbackToLatestData() && diagnosis.status() != org.ruoyi.fault.domain.enums.DiagnosisStatus.DATA_INSUFFICIENT);
    }

    private static void appendCodeSentence(StringBuilder out, String prefix, List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            out.append(prefix).append("（无）。");
            return;
        }
        out.append(prefix).append(" ").append(String.join("、", codes)).append("。");
    }

    private static String formatTime(LocalDateTime value) {
        return value == null ? "无" : TIME_FORMATTER.format(value);
    }

}
