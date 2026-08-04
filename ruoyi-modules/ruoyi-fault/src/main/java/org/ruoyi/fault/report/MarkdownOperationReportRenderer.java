package org.ruoyi.fault.report;

import org.ruoyi.fault.domain.code.FaultCodeType;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 设备运行与状态报告的 Markdown 渲染器，提供两种形态：
 * <ul>
 *     <li>{@link #renderConcise}：聊天/查看窗口的精简版，按“状态结论 → 故障/报警卡片 → 处理建议 →
 *     运行摘要 → 结论边界”组织，不含审计信息、原始状态码和宽表格；</li>
 *     <li>{@link #renderFull}：下载留存的完整版，在结论前置的基础上保留时间线、数据质量、
 *     证据链与报告元数据。</li>
 * </ul>
 * 内容全部取自 {@link OperationReportResult} 中的结构化事实，空内容渲染为“无”而不省略章节；
 * 回退窗口与数据不足在正文首行显式说明“当前状态无法确认”。本类是纯函数。
 */
public final class MarkdownOperationReportRenderer {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    /** 事件时间线最多渲染条数，超出时截断并说明。 */
    private static final int MAX_EVENT_ROWS = 100;

    /** 指标键，与 fault.diagnosis.metric-units 配置键一致。 */
    private static final String METRIC_ACTUAL_POWER = "actual-power";
    private static final String METRIC_MOTOR_TEMP = "motor-temp";
    private static final String METRIC_INVERTER_TEMP = "inverter-temp";
    private static final String METRIC_MOTOR_LOAD_RATE = "motor-load-rate";
    private static final String METRIC_INVERTER_LOAD_RATE = "inverter-load-rate";

    private MarkdownOperationReportRenderer() {
    }

    /**
     * 渲染聊天/查看窗口的精简版报告。
     *
     * @param narrative   已通过安全校验的“处理建议”文字；为 null 时使用确定性内容
     * @param metricUnits 指标键 -> 单位；未配置单位的指标不展示
     */
    public static String renderConcise(OperationReportResult result, String narrative,
                                       Map<String, String> metricUnits) {
        StringBuilder out = new StringBuilder();
        out.append("# 设备运行与状态报告\n\n");
        appendUnconfirmedBanner(out, result);
        out.append("**设备：").append(result.deviceName()).append(" · 逆变器：")
            .append(blankToNone(result.inverterName())).append(" · 状态：")
            .append(result.healthStatus().getDisplayName()).append("**\n\n");
        out.append("分析区间：").append(formatTime(result.requestedStartTime())).append(" ~ ")
            .append(formatTime(result.requestedEndTime())).append('\n');
        out.append("最新数据：").append(formatTime(result.telemetry().latestObservedAt())).append('\n');
        DataQualitySummary quality = result.telemetry().quality();
        if (quality != null) {
            out.append("数据完整率：").append(percent(quality.completeness())).append('\n');
        }
        appendCodeCards(out, result, true);
        appendRecommendations(out, "处理建议", result.diagnosis(), narrative);
        appendConciseMetrics(out, result.telemetry(), metricUnits);
        appendConclusionBoundary(out, result.diagnosis());
        out.append("\n---\n\n完整明细（事件时间线、数据质量、证据溯源、报告编号 ")
            .append(result.reportCode()).append("）请下载运行报告。\n");
        return out.toString();
    }

    /**
     * 渲染下载留存的完整版报告。
     *
     * @param narrative   已通过安全校验的“代码说明与处理建议”文字；为 null 时使用确定性内容
     * @param metricUnits 指标键 -> 单位；未配置单位的指标不展示
     */
    public static String renderFull(OperationReportResult result, String narrative,
                                    Map<String, String> metricUnits) {
        StringBuilder out = new StringBuilder();
        out.append("# 设备运行与状态报告\n");
        appendMetaSection(out, result);
        appendConclusionSection(out, result);
        out.append("\n## 3. 故障与报警信息\n\n");
        appendCodeCards(out, result, false);
        appendRecommendations(out, "4. 代码说明与处理建议", result.diagnosis(), narrative);
        appendMetricsSection(out, result.telemetry(), metricUnits);
        appendQualitySection(out, result.telemetry());
        appendEventsSection(out, result.telemetry());
        appendLimitationsSection(out, result.diagnosis());
        appendEvidenceSection(out, result);
        out.append("\n---\n\n本报告由故障诊断系统自动生成，当前诊断依据为设备故障码与故障知识库，")
            .append("尚未结合温度、电流等趋势模型，结论供运维参考。\n");
        return out.toString();
    }

    /**
     * 历史回退或数据不足时，正文首行必须明确“当前状态无法确认”，避免把历史数据当成当前状态。
     */
    private static void appendUnconfirmedBanner(StringBuilder out, OperationReportResult result) {
        TelemetryQueryResult telemetry = result.telemetry();
        if (telemetry.fallbackToLatestData()) {
            out.append("当前状态无法确认：请求窗口无数据，以下为最近可用数据窗口（")
                .append(formatTime(telemetry.startTime())).append(" ~ ")
                .append(formatTime(telemetry.endTime())).append("）的历史分析。\n\n");
            return;
        }
        if (result.diagnosis().status() == DiagnosisStatus.DATA_INSUFFICIENT) {
            out.append("当前状态无法确认：窗口内数据缺失或不足。\n\n");
        }
    }

    /** 故障/报警卡片：标题含代码类型、代码与窗口末尾的恢复状态，明细为采样命中口径。 */
    private static void appendCodeCards(StringBuilder out, OperationReportResult result, boolean headingCards) {
        TelemetryQueryResult telemetry = result.telemetry();
        OperationStatistics operation = telemetry.operation();
        List<CodeOccurrence> faults = operation == null ? List.of() : operation.faultCodeOccurrences();
        List<CodeOccurrence> alarms = operation == null ? List.of() : operation.alarmCodeOccurrences();
        if (faults.isEmpty() && alarms.isEmpty()) {
            if (headingCards) {
                out.append("\n## 故障与报警\n\n窗口内未发现故障码或报警码。\n");
            } else {
                out.append("窗口内未发现故障码或报警码。\n");
            }
        } else {
            Map<String, CandidateFault> candidates = candidateFaultsByCode(result.diagnosis());
            List<StatusEvent> events = telemetry.statusEvents();
            List<CodeOccurrence> all = new ArrayList<>(faults);
            all.addAll(alarms);
            for (int index = 0; index < all.size(); index++) {
                CodeOccurrence occurrence = all.get(index);
                FaultCodeType type = index < faults.size() ? FaultCodeType.FAULT : FaultCodeType.ALARM;
                appendCodeCard(out, occurrence, type, candidates, events, headingCards, index == 0);
            }
            if (faults.isEmpty()) {
                out.append("\n故障码：未发现 F 类故障码。\n");
            }
        }
        if (!telemetry.unknownCodes().isEmpty()) {
            out.append("\n未识别代码：").append(String.join("、", telemetry.unknownCodes()))
                .append("（未升级为故障或报警）。\n");
        }
    }

    private static void appendCodeCard(StringBuilder out, CodeOccurrence occurrence, FaultCodeType type,
                                       Map<String, CandidateFault> candidates, List<StatusEvent> events,
                                       boolean headingCard, boolean firstCard) {
        String title = type.term() + " " + occurrence.code();
        String recovery = codeRecoveryText(occurrence.code(), type, events);
        if (recovery != null) {
            title += " · " + recovery;
        }
        if (headingCard) {
            out.append("\n## ").append(title).append("\n\n");
        } else {
            if (!firstCard) {
                out.append('\n');
            }
            out.append("**").append(title).append("**\n\n");
        }
        out.append("- 发生时间：").append(formatTime(occurrence.firstObservedAt())).append(" ~ ")
            .append(formatTime(occurrence.lastObservedAt())).append('\n');
        out.append("- 采样命中：").append(occurrence.sampleCount()).append(" 条记录\n");
        out.append("- 手册匹配：").append(knowledgeStatusText(candidates.get(occurrence.code()))).append('\n');
    }

    /**
     * 代码在窗口末尾的恢复状态：事件按时间升序且仅在状态变化时产生，
     * 最后一个事件即代表窗口末尾状态；末尾事件仍携带该代码为“持续中”，否则为“已恢复”。
     * 无事件时不标注（防御分支：有代码出现就至少有一个事件）。
     */
    private static String codeRecoveryText(String code, FaultCodeType type, List<StatusEvent> events) {
        if (events.isEmpty()) {
            return null;
        }
        StatusEvent last = events.get(events.size() - 1);
        String activeCode = type == FaultCodeType.ALARM ? last.alarmCode() : last.faultCode();
        return code.equals(activeCode) ? "持续中" : "已恢复";
    }

    /** 处理建议：优先呈现通过安全校验的模型叙事，失败时回退确定性内容。 */
    private static void appendRecommendations(StringBuilder out, String title, DiagnosisResult diagnosis,
                                              String narrative) {
        out.append("\n## ").append(title).append("\n\n");
        if (narrative != null && !narrative.isBlank()) {
            out.append(narrative.trim()).append('\n');
            return;
        }
        List<CandidateFault> candidates = diagnosis.candidateFaults();
        if (!candidates.isEmpty()) {
            // 知识匹配状态已在代码卡片展示，此处只强调代码类型，避免重复结论
            for (CandidateFault candidate : candidates) {
                out.append("- ").append(candidate.faultCode()).append(" 是")
                    .append(candidate.codeType().term()).append("码。\n");
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

    /** 精简版运行摘要：只渲染已配置单位的指标；没有可展示指标时整节省略。 */
    private static void appendConciseMetrics(StringBuilder out, TelemetryQueryResult telemetry,
                                             Map<String, String> metricUnits) {
        List<String> lines = metricLines(telemetry, metricUnits, false);
        if (lines.isEmpty()) {
            return;
        }
        out.append("\n## 运行摘要\n\n");
        for (String line : lines) {
            out.append("- ").append(line).append('\n');
        }
    }

    /** 结论边界：说明判断依据，避免把“未发现代码”扩大成“设备完全健康”。 */
    private static void appendConclusionBoundary(StringBuilder out, DiagnosisResult diagnosis) {
        out.append("\n## 结论边界\n\n");
        out.append("本报告依据遥测中的显式故障码/报警码与故障知识库判断，")
            .append("未使用温度、电流等趋势推断根因，结论供运维参考。\n");
        if (diagnosis.partial()) {
            out.append("本次诊断为降级结果，请结合完整报告的限制说明使用。\n");
        }
    }

    /** 1. 报告基本信息（仅完整版）。 */
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

    /** 2. 运行状态结论（仅完整版）；回退/数据不足时首行说明当前状态无法确认。 */
    private static void appendConclusionSection(StringBuilder out, OperationReportResult result) {
        out.append("\n## 2. 运行状态结论\n\n");
        appendUnconfirmedBanner(out, result);
        out.append("**设备状态：").append(result.healthStatus().getDisplayName()).append("**\n\n");
        out.append(result.summary()).append('\n');
        if (result.diagnosis().partial()) {
            out.append("\n注意：本次诊断为降级结果，部分步骤未完整执行。\n");
        }
    }

    /** 5. 运行摘要（仅完整版）；未配置单位的指标不展示。 */
    private static void appendMetricsSection(StringBuilder out, TelemetryQueryResult telemetry,
                                             Map<String, String> metricUnits) {
        out.append("\n## 5. 运行摘要\n\n");
        TelemetryStatistics statistics = telemetry.statistics();
        if (statistics == null || statistics.sampleCount() == 0) {
            out.append("窗口内无有效运行数据。\n");
            return;
        }
        List<String> lines = metricLines(telemetry, metricUnits, true);
        if (lines.isEmpty()) {
            out.append("运行指标单位尚未确认，暂不展示。\n");
            return;
        }
        out.append("有效样本 ").append(statistics.sampleCount()).append(" 条。\n\n");
        for (String line : lines) {
            out.append("- ").append(line).append('\n');
        }
    }

    /** 已配置单位的指标行；withPeak 时附带峰值出现时间。 */
    private static List<String> metricLines(TelemetryQueryResult telemetry, Map<String, String> metricUnits,
                                            boolean withPeak) {
        TelemetryStatistics statistics = telemetry.statistics();
        if (statistics == null || statistics.sampleCount() == 0 || metricUnits == null || metricUnits.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        addRangeLine(lines, metricUnits, METRIC_ACTUAL_POWER, "实际功率",
            statistics.minActualPower(), statistics.avgActualPower(), statistics.maxActualPower());
        addRangeLine(lines, metricUnits, METRIC_MOTOR_TEMP, "电机温度",
            statistics.minMotorTemp(), statistics.avgMotorTemp(), statistics.maxMotorTemp());
        addRangeLine(lines, metricUnits, METRIC_INVERTER_TEMP, "变频器温度",
            statistics.minInverterTemp(), statistics.avgInverterTemp(), statistics.maxInverterTemp());
        addMaxLine(lines, metricUnits, METRIC_MOTOR_LOAD_RATE, "电机负载率", statistics.maxMotorLoadRate());
        addMaxLine(lines, metricUnits, METRIC_INVERTER_LOAD_RATE, "变频器负载率", statistics.maxInverterLoadRate());
        if (withPeak) {
            addPeakLine(lines, telemetry.operation(), metricUnits);
        }
        return List.copyOf(lines);
    }

    private static void addRangeLine(List<String> lines, Map<String, String> metricUnits, String key,
                                     String label, Double min, Double avg, Double max) {
        String unit = unitOf(metricUnits, key);
        if (unit == null) {
            return;
        }
        lines.add(label + "（" + unit + "）：最低 " + number(min) + "，平均 " + number(avg)
            + "，最高 " + number(max));
    }

    private static void addMaxLine(List<String> lines, Map<String, String> metricUnits, String key,
                                   String label, Double max) {
        String unit = unitOf(metricUnits, key);
        if (unit == null) {
            return;
        }
        lines.add(label + "（" + unit + "）：最高 " + number(max));
    }

    private static void addPeakLine(List<String> lines, OperationStatistics operation,
                                    Map<String, String> metricUnits) {
        if (operation == null || lines.isEmpty()) {
            return;
        }
        LocalDateTime tempAt = unitOf(metricUnits, METRIC_MOTOR_TEMP) != null ? operation.maxMotorTempAt() : null;
        LocalDateTime loadAt = unitOf(metricUnits, METRIC_MOTOR_LOAD_RATE) != null
            ? operation.maxMotorLoadRateAt() : null;
        if (tempAt == null && loadAt == null) {
            return;
        }
        StringBuilder peak = new StringBuilder("峰值出现时间：");
        if (tempAt != null) {
            peak.append("电机温度最高值出现于 ").append(formatTime(tempAt));
        }
        if (tempAt != null && loadAt != null) {
            peak.append("；");
        }
        if (loadAt != null) {
            peak.append("电机负载率最高值出现于 ").append(formatTime(loadAt));
        }
        lines.add(peak.append("。").toString());
    }

    /** 6. 数据质量（仅完整版）。 */
    private static void appendQualitySection(StringBuilder out, TelemetryQueryResult telemetry) {
        out.append("\n## 6. 数据质量\n\n");
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

    /** 7. 状态与事件时间线（仅完整版）；状态值按当前数据契约翻译，不直接展示原始码。 */
    private static void appendEventsSection(StringBuilder out, TelemetryQueryResult telemetry) {
        out.append("\n## 7. 状态与事件时间线\n\n");
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
                .append(statusText(event.status())).append(" | ")
                .append(blankToNone(event.faultCode())).append(" | ")
                .append(blankToNone(event.alarmCode())).append(" |\n");
        }
        if (events.size() > rendered) {
            out.append("\n事件共 ").append(events.size()).append(" 条，仅展示前 ")
                .append(rendered).append(" 条。\n");
        }
    }

    /**
     * 状态值翻译，依据当前遥测数据契约：0 为正常段，42 为异常段。
     * 真实数据字典到位后在此补充已知取值；未识别取值保留原值，不丢失信息。
     */
    private static String statusText(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "—";
        }
        String status = rawStatus.trim();
        return switch (status) {
            case "0" -> "正常";
            case "42" -> "异常";
            default -> "未识别（原值 " + status + "）";
        };
    }

    /** 8. 限制说明（仅完整版）。 */
    private static void appendLimitationsSection(StringBuilder out, DiagnosisResult diagnosis) {
        out.append("\n## 8. 限制说明\n\n");
        Set<String> limitations = new LinkedHashSet<>(diagnosis.limitations());
        if (limitations.isEmpty()) {
            out.append("- 无\n");
            return;
        }
        for (String limitation : limitations) {
            out.append("- ").append(limitation).append('\n');
        }
    }

    /** 9. 证据与溯源（仅完整版）；证据类型使用中文名，title 与类型名相同时不重复。 */
    private static void appendEvidenceSection(StringBuilder out, OperationReportResult result) {
        out.append("\n## 9. 证据与溯源\n\n");
        List<EvidenceReference> evidence = result.diagnosis().evidenceIndex().stream()
            .filter(reference -> reference != null && reference.userVisible()).toList();
        if (evidence.isEmpty()) {
            out.append("本次没有可引用的持久化证据。\n");
            return;
        }
        for (EvidenceReference reference : evidence) {
            appendEvidenceLine(out, reference);
        }
        out.append("\n诊断过程已按证据链持久化，可凭上述编号在系统中核验。\n");
    }

    private static void appendEvidenceLine(StringBuilder out, EvidenceReference reference) {
        String typeName = reference.evidenceType() == null ? null : reference.evidenceType().getDisplayName();
        String title = reference.title();
        boolean hasTitle = title != null && !title.isBlank();
        boolean titleDistinct = hasTitle && typeName != null && !title.equals(typeName);
        out.append("- ").append(blankToNone(reference.evidenceCode())).append(' ');
        if (hasTitle) {
            out.append(titleDistinct ? typeName + "：" + title : title);
        } else {
            out.append(blankToNone(typeName));
        }
        if (reference.summary() != null && !reference.summary().isBlank()) {
            out.append(titleDistinct ? "，" : "：").append(reference.summary());
        }
        out.append('\n');
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

    private static String unitOf(Map<String, String> metricUnits, String key) {
        if (metricUnits == null) {
            return null;
        }
        String unit = metricUnits.get(key);
        return unit == null || unit.isBlank() ? null : unit.trim();
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
