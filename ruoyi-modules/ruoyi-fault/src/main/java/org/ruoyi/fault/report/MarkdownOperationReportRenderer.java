package org.ruoyi.fault.report;

import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.telemetry.model.CodeOccurrence;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.OperationStatistics;
import org.ruoyi.fault.telemetry.model.StatusEvent;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetryStatistics;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 设备运行与状态报告的 Markdown 渲染器。
 * <p>
 * 骨架固定为 10 个章节，内容全部取自 {@link OperationReportResult} 中的结构化事实，
 * 空内容渲染为“无”而不省略章节；回退窗口与数据不足有显式提示。本类是纯函数，
 * 后续新增 HTML/PDF 渲染器时业务逻辑不变。
 */
public final class MarkdownOperationReportRenderer {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    /** 事件时间线最多渲染条数，超出时截断并说明。 */
    private static final int MAX_EVENT_ROWS = 100;

    private MarkdownOperationReportRenderer() {
    }

    public static String render(OperationReportResult result) {
        return render(result, null);
    }

    /**
     * 渲染完整报告。
     *
     * @param narrative 已通过安全校验的“代码说明与处理建议”文字；为 null 时使用确定性内容
     */
    public static String render(OperationReportResult result, String narrative) {
        StringBuilder out = new StringBuilder();
        out.append("# 设备运行与状态报告\n");
        appendMetaSection(out, result);
        appendSummarySection(out, result);
        appendQualitySection(out, result.telemetry());
        appendMetricsSection(out, result.telemetry());
        appendEventsSection(out, result.telemetry());
        appendCodesSection(out, result);
        appendDiagnosisSection(out, result);
        appendNarrativeSection(out, result.diagnosis(), narrative);
        appendLimitationsSection(out, result.diagnosis());
        appendEvidenceSection(out, result);
        out.append("\n---\n\n本报告由故障诊断系统自动生成，当前诊断依据为设备故障码与故障知识库，")
            .append("尚未结合温度、电流等趋势模型，结论供运维参考。\n");
        return out.toString();
    }

    /** 1. 报告基本信息。 */
    private static void appendMetaSection(StringBuilder out, OperationReportResult result) {
        TelemetryQueryResult telemetry = result.telemetry();
        out.append("\n## 1. 报告基本信息\n\n");
        out.append("| 项目 | 内容 |\n| --- | --- |\n");
        out.append("| 报告编号 | ").append(result.reportCode()).append(" |\n");
        out.append("| 设备 | ").append(result.deviceName()).append(" |\n");
        out.append("| 逆变器 | ").append(result.inverterName()).append(" |\n");
        out.append("| 请求窗口 | ").append(formatTime(result.requestedStartTime())).append(" ~ ")
            .append(formatTime(result.requestedEndTime())).append(" |\n");
        out.append("| 实际分析窗口 | ").append(formatTime(telemetry.startTime())).append(" ~ ")
            .append(formatTime(telemetry.endTime()));
        if (telemetry.fallbackToLatestData()) {
            out.append("（请求窗口无数据，已回退到最近可用数据窗口）");
        }
        out.append(" |\n");
        out.append("| 生成时间 | ").append(formatTime(result.generatedAt())).append(" |\n");
        out.append("| 数据源摘要 | ").append(blankToNone(telemetry.sourceDigest())).append(" |\n");
    }

    /** 2. 运行状态摘要。 */
    private static void appendSummarySection(StringBuilder out, OperationReportResult result) {
        out.append("\n## 2. 运行状态摘要\n\n");
        out.append("**设备状态：").append(result.healthStatus().getDisplayName()).append("**\n\n");
        out.append(result.summary()).append('\n');
    }

    /** 3. 数据质量。 */
    private static void appendQualitySection(StringBuilder out, TelemetryQueryResult telemetry) {
        out.append("\n## 3. 数据质量\n\n");
        DataQualitySummary quality = telemetry.quality();
        if (quality == null) {
            out.append("无数据质量摘要。\n");
        } else {
            out.append("| 指标 | 值 |\n| --- | --- |\n");
            out.append("| 原始记录数 | ").append(quality.rawRecordCount()).append(" 条 |\n");
            out.append("| 有效记录数 | ").append(quality.validRecordCount()).append(" 条 |\n");
            out.append("| 重复记录数 | ").append(quality.duplicateCount()).append(" 条 |\n");
            out.append("| 无效时间记录数 | ").append(quality.invalidTimeCount()).append(" 条 |\n");
            out.append("| 缺失采样点数 | ").append(quality.gapCount()).append(" 个 |\n");
            out.append("| 数据完整率 | ").append(percent(quality.completeness())).append(" |\n");
            out.append("| 数据是否充足 | ").append(quality.sufficient() ? "是" : "否").append(" |\n");
        }
        if (!telemetry.codeNormalizationNotes().isEmpty()) {
            out.append("\n数据质量说明：\n");
            for (String note : telemetry.codeNormalizationNotes()) {
                out.append("- ").append(note).append('\n');
            }
        }
    }

    /** 4. 关键运行指标。 */
    private static void appendMetricsSection(StringBuilder out, TelemetryQueryResult telemetry) {
        out.append("\n## 4. 关键运行指标\n\n");
        TelemetryStatistics statistics = telemetry.statistics();
        if (statistics == null || statistics.sampleCount() == 0) {
            out.append("窗口内无有效运行数据。\n");
            return;
        }
        out.append("有效样本 ").append(statistics.sampleCount()).append(" 条。\n\n");
        out.append("| 指标 | 最低 | 平均 | 最高 |\n| --- | --- | --- | --- |\n");
        out.append("| 实际功率 | ").append(number(statistics.minActualPower())).append(" | ")
            .append(number(statistics.avgActualPower())).append(" | ")
            .append(number(statistics.maxActualPower())).append(" |\n");
        out.append("| 电机温度 | ").append(number(statistics.minMotorTemp())).append(" | ")
            .append(number(statistics.avgMotorTemp())).append(" | ")
            .append(number(statistics.maxMotorTemp())).append(" |\n");
        out.append("| 变频器温度 | ").append(number(statistics.minInverterTemp())).append(" | ")
            .append(number(statistics.avgInverterTemp())).append(" | ")
            .append(number(statistics.maxInverterTemp())).append(" |\n");
        out.append("| 电机负载率 | — | — | ").append(number(statistics.maxMotorLoadRate())).append(" |\n");
        out.append("| 变频器负载率 | — | — | ").append(number(statistics.maxInverterLoadRate())).append(" |\n");
        OperationStatistics operation = telemetry.operation();
        if (operation != null && (operation.maxMotorTempAt() != null || operation.maxMotorLoadRateAt() != null)) {
            out.append("\n峰值出现时间：");
            if (operation.maxMotorTempAt() != null) {
                out.append("电机温度最高值出现于 ").append(formatTime(operation.maxMotorTempAt()));
                if (operation.maxMotorLoadRateAt() != null) {
                    out.append("；");
                }
            }
            if (operation.maxMotorLoadRateAt() != null) {
                out.append("电机负载率最高值出现于 ").append(formatTime(operation.maxMotorLoadRateAt()));
            }
            out.append("。\n");
        }
    }

    /** 5. 状态与事件时间线。 */
    private static void appendEventsSection(StringBuilder out, TelemetryQueryResult telemetry) {
        out.append("\n## 5. 状态与事件时间线\n\n");
        List<StatusEvent> events = telemetry.statusEvents();
        if (events.isEmpty()) {
            out.append("窗口内未记录到状态变化事件。\n");
            return;
        }
        out.append("| 时间 | 状态 | 故障码 | 报警码 |\n| --- | --- | --- | --- |\n");
        int rendered = Math.min(events.size(), MAX_EVENT_ROWS);
        for (int index = 0; index < rendered; index++) {
            StatusEvent event = events.get(index);
            out.append("| ").append(formatTime(event.observedAt())).append(" | ")
                .append(blankToNone(event.status())).append(" | ")
                .append(blankToNone(event.faultCode())).append(" | ")
                .append(blankToNone(event.alarmCode())).append(" |\n");
        }
        if (events.size() > rendered) {
            out.append("\n事件共 ").append(events.size()).append(" 条，仅展示前 ")
                .append(rendered).append(" 条。\n");
        }
    }

    /** 6. 故障与报警信息。窄气泡内宽表会截断，使用要点列表呈现。 */
    private static void appendCodesSection(StringBuilder out, OperationReportResult result) {
        out.append("\n## 6. 故障与报警信息\n\n");
        TelemetryQueryResult telemetry = result.telemetry();
        OperationStatistics operation = telemetry.operation();
        List<CodeOccurrence> faults = operation == null ? List.of() : operation.faultCodeOccurrences();
        List<CodeOccurrence> alarms = operation == null ? List.of() : operation.alarmCodeOccurrences();
        if (faults.isEmpty() && alarms.isEmpty()) {
            out.append("本周期未发现故障码或报警码。\n");
        } else {
            Map<String, CandidateFault> candidates = candidateFaultsByCode(result.diagnosis());
            appendOccurrenceItems(out, faults, "故障", candidates);
            appendOccurrenceItems(out, alarms, "报警", candidates);
        }
        if (!telemetry.unknownCodes().isEmpty()) {
            out.append("\n未识别代码：").append(String.join("、", telemetry.unknownCodes()))
                .append("（未升级为故障或报警）。\n");
        }
    }

    private static void appendOccurrenceItems(StringBuilder out, List<CodeOccurrence> occurrences, String type,
                                              Map<String, CandidateFault> candidates) {
        for (CodeOccurrence occurrence : occurrences) {
            out.append("- ").append(occurrence.code()).append("（").append(type).append("码）：出现 ")
                .append(occurrence.sampleCount()).append(" 次，首次 ")
                .append(formatTime(occurrence.firstObservedAt())).append("，最近 ")
                .append(formatTime(occurrence.lastObservedAt())).append("；知识匹配：")
                .append(knowledgeStatusText(candidates.get(occurrence.code()))).append('\n');
        }
    }

    /** 7. 故障诊断。 */
    private static void appendDiagnosisSection(StringBuilder out, OperationReportResult result) {
        out.append("\n## 7. 故障诊断\n\n");
        DiagnosisResult diagnosis = result.diagnosis();
        if (diagnosis.status() == DiagnosisStatus.DATA_INSUFFICIENT) {
            out.append("数据不足，本周期无法给出确定性诊断结论。\n");
            return;
        }
        List<String> faults = diagnosis.faultCodes();
        List<String> alarms = diagnosis.alarmCodes();
        if (result.healthStatus() == ReportHealthStatus.FAULT) {
            out.append("检测到故障：").append(String.join("、", faults));
            if (!alarms.isEmpty()) {
                out.append("；同时存在报警：").append(String.join("、", alarms));
            }
            out.append("。\n");
        } else if (result.healthStatus() == ReportHealthStatus.ATTENTION) {
            out.append("存在报警：").append(String.join("、", alarms))
                .append("，未发现 F 类故障码。\n");
        } else {
            out.append("未发现显式故障码或报警码。\n");
        }
        if (diagnosis.partial()) {
            out.append("\n注意：本次诊断为降级结果，部分步骤未完整执行。\n");
        }
    }

    /** 8. 代码说明与处理建议：优先呈现通过安全校验的模型叙事，失败时回退确定性内容。 */
    private static void appendNarrativeSection(StringBuilder out, DiagnosisResult diagnosis, String narrative) {
        out.append("\n## 8. 代码说明与处理建议\n\n");
        if (narrative != null && !narrative.isBlank()) {
            out.append(narrative.trim()).append('\n');
            return;
        }
        List<CandidateFault> candidates = diagnosis.candidateFaults();
        if (!candidates.isEmpty()) {
            for (CandidateFault candidate : candidates) {
                out.append("- ").append(candidate.faultCode()).append(" 是")
                    .append(candidate.codeType().term()).append("码，知识匹配：")
                    .append(knowledgeStatusText(candidate)).append("。\n");
            }
            out.append("知识库内容仅为资料解释，不能替代对本设备实际参数的核对。\n");
        }
        List<String> recommendations = diagnosis.recommendations();
        if (recommendations.isEmpty()) {
            out.append("暂无针对本周期的处理建议，请结合后续运行数据持续观察。\n");
            return;
        }
        int index = 0;
        for (String recommendation : recommendations) {
            index++;
            out.append(index).append(". ").append(recommendation).append('\n');
        }
    }

    /** 9. 限制说明。 */
    private static void appendLimitationsSection(StringBuilder out, DiagnosisResult diagnosis) {
        out.append("\n## 9. 限制说明\n\n");
        Set<String> limitations = new LinkedHashSet<>(diagnosis.limitations());
        if (limitations.isEmpty()) {
            out.append("- 无\n");
            return;
        }
        for (String limitation : limitations) {
            out.append("- ").append(limitation).append('\n');
        }
    }

    /** 10. 证据与溯源。 */
    private static void appendEvidenceSection(StringBuilder out, OperationReportResult result) {
        out.append("\n## 10. 证据与溯源\n\n");
        List<EvidenceReference> evidence = result.diagnosis().evidenceIndex().stream()
            .filter(reference -> reference != null && reference.userVisible()).toList();
        if (evidence.isEmpty()) {
            out.append("本次没有可引用的持久化证据。\n");
            return;
        }
        for (EvidenceReference reference : evidence) {
            out.append("- ").append(blankToNone(reference.evidenceCode())).append(' ')
                .append(reference.evidenceType() == null ? "—" : reference.evidenceType().name()).append("：")
                .append(blankToNone(reference.title()));
            if (reference.summary() != null && !reference.summary().isBlank()) {
                out.append("，").append(reference.summary());
            }
            out.append('\n');
        }
        out.append("\n诊断过程已按证据链持久化，可凭上述编号在系统中核验。\n");
    }

    private static Map<String, CandidateFault> candidateFaultsByCode(DiagnosisResult diagnosis) {
        Map<String, CandidateFault> candidates = new LinkedHashMap<>();
        for (CandidateFault candidate : diagnosis.candidateFaults()) {
            candidates.putIfAbsent(candidate.faultCode(), candidate);
        }
        return candidates;
    }

    private static String knowledgeStatusText(CandidateFault candidate) {
        if (candidate == null) {
            return "未查询";
        }
        return switch (candidate.knowledgeStatus()) {
            case MATCHED -> {
                String sources = sourceDocuments(candidate.knowledgeEvidence());
                yield sources.isEmpty() ? "已匹配" : "已匹配：" + sources;
            }
            case NOT_FOUND -> "未找到知识依据";
            case FAILED -> "知识查询失败";
            case SKIPPED -> "未绑定知识库";
        };
    }

    private static String sourceDocuments(List<FaultKnowledgeEvidence> evidence) {
        Set<String> documents = new LinkedHashSet<>();
        if (evidence != null) {
            for (FaultKnowledgeEvidence item : evidence) {
                if (item != null && item.sourceDocument() != null && !item.sourceDocument().isBlank()) {
                    documents.add(item.sourceDocument());
                }
            }
        }
        return String.join("、", documents);
    }

    private static String formatTime(LocalDateTime value) {
        return value == null ? "—" : TIME_FORMATTER.format(value);
    }

    private static String blankToNone(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String number(Double value) {
        if (value == null) {
            return "—";
        }
        DecimalFormat format = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format.format(value);
    }

    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
    }

}
