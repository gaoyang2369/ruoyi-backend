package org.ruoyi.fault.report;

import cn.hutool.core.util.IdUtil;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.diagnosis.FaultDiagnosisOrchestrator;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.telemetry.model.CodeOccurrence;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.OperationStatistics;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
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
    private final FaultDiagnosisProperties properties;

    public OperationReportOrchestrator(TelemetryQueryService telemetryQueryService,
                                       FaultDiagnosisOrchestrator faultDiagnosisOrchestrator,
                                       FaultDiagnosisProperties properties) {
        this.telemetryQueryService = telemetryQueryService;
        this.faultDiagnosisOrchestrator = faultDiagnosisOrchestrator;
        this.properties = properties;
    }

    public OperationReportResult generate(DiagnosisCommand command) {
        TelemetryQueryResult telemetry = telemetryQueryService.queryTelemetry(
            command.deviceName(), command.inverterName(), command.startTime(), command.endTime());
        DiagnosisResult diagnosis = faultDiagnosisOrchestrator.diagnose(command, telemetry);
        ReportHealthStatus healthStatus = ReportHealthStatus.fromDiagnosisStatus(diagnosis.status());
        return new OperationReportResult(
            "RP-" + IdUtil.getSnowflakeNextId(),
            command.deviceName(),
            command.inverterName(),
            command.startTime(),
            command.endTime(),
            LocalDateTime.now(ZoneId.of(properties.getTimezone())),
            healthStatus,
            buildSummary(healthStatus, telemetry, diagnosis),
            telemetry,
            diagnosis);
    }

    /**
     * 运行结论段落：先说发生了什么，再给数据质量与诊断边界，全部取自结构化事实。
     */
    private String buildSummary(ReportHealthStatus healthStatus, TelemetryQueryResult telemetry,
                                DiagnosisResult diagnosis) {
        StringBuilder out = new StringBuilder();
        out.append("报告周期内设备状态：").append(healthStatus.getDisplayName()).append("。");
        switch (healthStatus) {
            case FAULT -> appendCodeSentence(out, "检测到故障码", telemetry.operation() == null
                ? null : telemetry.operation().faultCodeOccurrences());
            case ATTENTION -> appendCodeSentence(out, "存在报警码", telemetry.operation() == null
                ? null : telemetry.operation().alarmCodeOccurrences());
            case NORMAL -> out.append("未发现显式故障码或报警码。");
            case UNKNOWN -> out.append("无数据或数据质量不足，无法确认设备状态。");
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
        return out.toString();
    }

    private void appendCodeSentence(StringBuilder out, String prefix, List<CodeOccurrence> occurrences) {
        if (occurrences == null || occurrences.isEmpty()) {
            out.append(prefix).append("（无出现明细）。");
            return;
        }
        out.append(prefix).append(" ");
        for (int index = 0; index < occurrences.size(); index++) {
            CodeOccurrence occurrence = occurrences.get(index);
            if (index > 0) {
                out.append("；");
            }
            out.append(occurrence.code()).append(" 出现 ").append(occurrence.sampleCount()).append(" 次");
            if (occurrence.firstObservedAt() != null) {
                out.append("（首次 ").append(formatTime(occurrence.firstObservedAt()));
                if (occurrence.lastObservedAt() != null
                    && !occurrence.lastObservedAt().equals(occurrence.firstObservedAt())) {
                    out.append("，最近 ").append(formatTime(occurrence.lastObservedAt()));
                }
                out.append("）");
            }
        }
        out.append("。");
    }

    private static String formatTime(LocalDateTime value) {
        return value == null ? "无" : TIME_FORMATTER.format(value);
    }

}
