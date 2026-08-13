package org.ruoyi.fault.report;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.evidence.enums.EvidenceType;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.telemetry.model.CodeOccurrence;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.OperationStatistics;
import org.ruoyi.fault.telemetry.model.StatusEvent;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetryStatistics;
import org.ruoyi.fault.telemetry.model.TelemetryStatisticsResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 精简版/完整版渲染行为测试；全文快照见 {@link OperationReportSnapshotTest}。 */
@Tag("dev")
class MarkdownOperationReportRendererTest {

    @Test
    void conciseShowsStatusCardsAndRecommendationsWithoutAuditDetails() {
        String markdown = MarkdownOperationReportRenderer.renderConcise(faultResult(), null, Map.of());

        assertTrue(markdown.startsWith("# 设备运行与状态报告"));
        assertTrue(markdown.contains("**设备：G120电机1 · 逆变器：INV-1 · 状态：故障**"));
        assertTrue(markdown.contains("## 故障 F30005 · 持续中"));
        assertTrue(markdown.contains("- 采样命中：3 条记录"));
        assertTrue(markdown.contains("- 手册匹配：已匹配：G120故障手册"));
        assertTrue(markdown.contains("## 处理建议"));
        assertTrue(markdown.contains("1. 检查负载"));
        // 审计信息不进入聊天精简版
        assertFalse(markdown.contains("sha256-digest"));
        assertFalse(markdown.contains("报告编号 |"));
        assertFalse(markdown.contains("| 时间 | 状态 |"));
        assertFalse(markdown.contains("TELEMETRY"));
    }

    @Test
    void conciseAlarmCardShowsRecoveredWhenCodeClearedBeforeWindowEnd() {
        String markdown = MarkdownOperationReportRenderer.renderConcise(alarmRecoveredResult(), null, Map.of());

        assertTrue(markdown.contains("## 报警 A07089 · 已恢复"));
        assertTrue(markdown.contains("故障码：未发现 F 类故障码。"));
        assertFalse(markdown.contains("出现 30 次"));
    }

    @Test
    void conciseAlarmCardShowsOngoingWhenCodeActiveAtWindowEnd() {
        String markdown = MarkdownOperationReportRenderer.renderConcise(alarmOngoingResult(), null, Map.of());

        assertTrue(markdown.contains("## 报警 A07089 · 持续中"));
    }

    @Test
    void conciseNormalReportWithoutCodes() {
        String markdown = MarkdownOperationReportRenderer.renderConcise(
            result(ReportHealthStatus.NORMAL, DiagnosisStatus.NO_EXPLICIT_FAULT,
                OperationStatistics.empty(), List.of(), List.of(), normalEvents(), List.of(),
                List.of(), List.of(), false, List.of()), null, Map.of());

        assertTrue(markdown.contains("## 故障与报警"));
        assertTrue(markdown.contains("窗口内未发现故障码或报警码。"));
    }

    @Test
    void conciseOmitsMetricsSectionWhenNoUnitConfigured() {
        String markdown = MarkdownOperationReportRenderer.renderConcise(faultResult(), null, Map.of());

        assertFalse(markdown.contains("## 运行摘要"));
        assertFalse(markdown.contains("实际功率"));
    }

    @Test
    void conciseRendersOnlyConfiguredMetricsWithUnits() {
        String markdown = MarkdownOperationReportRenderer.renderConcise(
            faultResult(), null, Map.of("motor-temp", "℃"));

        assertTrue(markdown.contains("## 运行摘要"));
        assertTrue(markdown.contains("电机温度（℃）：最低 42.1，平均 58.3，最高 76.2"));
        assertFalse(markdown.contains("实际功率"));
        assertFalse(markdown.contains("电机负载率"));
    }

    @Test
    void conciseFallbackFirstLineStatesCurrentStatusUnconfirmed() {
        String markdown = MarkdownOperationReportRenderer.renderConcise(
            result(ReportHealthStatus.NORMAL, DiagnosisStatus.NO_EXPLICIT_FAULT,
                OperationStatistics.empty(), List.of(), List.of(), normalEvents(), List.of(),
                List.of(), List.of(), true, List.of()), null, Map.of());

        assertTrue(markdown.startsWith("# 设备运行与状态报告\n\n当前状态无法确认：请求窗口无数据"));
    }

    @Test
    void conciseDataInsufficientStatesCurrentStatusUnconfirmed() {
        String markdown = MarkdownOperationReportRenderer.renderConcise(
            result(ReportHealthStatus.UNKNOWN, DiagnosisStatus.DATA_INSUFFICIENT,
                OperationStatistics.empty(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), false, List.of()), null, Map.of());

        assertTrue(markdown.contains("当前状态无法确认：窗口内数据缺失或不足。"));
    }

    @Test
    void conciseUsesNarrativeWhenProvided() {
        String markdown = MarkdownOperationReportRenderer.renderConcise(faultResult(),
            "F30005 与电机过载相关[EV-002]，建议检查负载。", Map.of());

        assertTrue(markdown.contains("## 处理建议"));
        assertTrue(markdown.contains("F30005 与电机过载相关[EV-002]，建议检查负载。"));
        assertFalse(markdown.contains("暂无针对本周期的处理建议"));
    }

    @Test
    void fullKeepsAuditInfoMetadataTimelineAndEvidence() {
        String markdown = MarkdownOperationReportRenderer.renderFull(faultResult(), null, Map.of());

        for (int section = 1; section <= 9; section++) {
            assertTrue(markdown.contains("## " + section + ". "), "缺少章节 " + section);
        }
        assertTrue(markdown.contains("RP-1"));
        assertTrue(markdown.contains("sha256-digest"));
        assertTrue(markdown.contains("运行指标单位尚未确认，暂不展示。"));
        // 状态翻译，不展示原始状态码
        assertTrue(markdown.contains("| 2026-08-04 10:05:00 | 异常 | F30005 | — |"));
        assertTrue(markdown.contains("| 2026-08-04 10:20:00 | 正常 | — | — |"));
        assertFalse(markdown.contains("| FAULT |"));
        // 证据类型中文化，且与 title 相同时不重复
        assertTrue(markdown.contains("- EV-001 遥测记录：窗口内 10 条有效数据"));
        assertFalse(markdown.contains("TELEMETRY"));
    }

    @Test
    void fullTranslatesUnknownStatusKeepsRawValue() {
        OperationReportResult result = result(ReportHealthStatus.NORMAL, DiagnosisStatus.NO_EXPLICIT_FAULT,
            OperationStatistics.empty(), List.of(), List.of(),
            List.of(new StatusEvent(BASE_TIME, "99", null, null)), List.of(),
            List.of(), List.of(), false, List.of());

        String markdown = MarkdownOperationReportRenderer.renderFull(result, null, Map.of());

        assertTrue(markdown.contains("未识别（原值 99）"));
    }

    @Test
    void fullShowsMetricsWithConfiguredUnitsAndPeakTimes() {
        String markdown = MarkdownOperationReportRenderer.renderFull(
            faultResult(), null, Map.of("motor-temp", "℃", "motor-load-rate", "%"));

        assertTrue(markdown.contains("电机温度（℃）：最低 42.1，平均 58.3，最高 76.2"));
        assertTrue(markdown.contains("电机负载率（%）：最高 104.3"));
        assertTrue(markdown.contains("峰值出现时间：电机温度最高值出现于 2026-08-04 10:15:00；"
            + "电机负载率最高值出现于 2026-08-04 10:17:00。"));
    }

    @Test
    void rendererUsesNarrativeFrozenInReportSnapshot() {
        OperationReportResult report = faultResult().withNarrative(
            new OperationReportResult.ReportNarrative("模型归纳内容", null, null, List.of(), null));

        String markdown = MarkdownOperationReportRenderer.renderFull(report, null, Map.of());

        assertTrue(markdown.contains("模型归纳内容"));
    }

    @Test
    void rendererShowsNewMetricsAndStructuredTrendFacts() {
        OperationReportResult base = faultResult();
        OperationReportResult result = new OperationReportResult(base.metadata(), base.asset(), base.period(),
            base.periodStatus(), base.currentStatus(), base.summary(), base.dataQuality(), base.metricUnits(),
            base.dataCompleteness(),
            List.of(new OperationReportResult.Metric("dcVoltage", null, 620D, 610D, 630D, 10, null),
                new OperationReportResult.Metric("currentActual", null, 12D, 10D, 14D, 10, null)),
            List.of(new OperationReportResult.Trend("dcVoltage", List.of(
                new OperationReportResult.TrendPoint(BASE_TIME, 620D, 2L),
                new OperationReportResult.TrendPoint(BASE_TIME.plusMinutes(1), 625D, 3L)))),
            base.events(), base.statusTimeline(), base.diagnosis(), base.recommendations(), base.evidence(),
            base.narrative(), base.limitations(), base.diagnosisDetail());

        String markdown = MarkdownOperationReportRenderer.renderConcise(result, null,
            Map.of("dc-voltage", "V", "current-actual", "A"));

        assertTrue(markdown.contains("直流电压（V）：最低 610，平均 620，最高 630"));
        assertTrue(markdown.contains("实际电流（A）：最低 10，平均 12，最高 14"));
        assertTrue(markdown.contains("## 运行趋势"));
        assertTrue(markdown.contains("2026-08-04 10:00:00 620（2 条）"));
    }

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 4, 10, 0);

    /** 故障持续中：窗口末尾事件仍携带 F30005。 */
    private static OperationReportResult faultResult() {
        LocalDateTime first = BASE_TIME.plusMinutes(5);
        OperationStatistics operation = new OperationStatistics(first.plusMinutes(10), first.plusMinutes(12),
            List.of(new CodeOccurrence("F30005", 3, first, first.plusMinutes(2))), List.of());
        CandidateFault candidate = new CandidateFault("F30005", FaultCodeType.FAULT,
            KnowledgeLookupStatus.MATCHED,
            List.of(new FaultKnowledgeEvidence(7L, "doc", "G120故障手册", "fragment", 0, "电机过载")),
            List.of("EV-003"));
        EvidenceReference evidence = new EvidenceReference(1L, "EV-001", EvidenceType.TELEMETRY,
            "遥测记录", "窗口内 10 条有效数据", true);
        List<StatusEvent> events = List.of(
            new StatusEvent(first, "42", "F30005", null),
            new StatusEvent(BASE_TIME.plusMinutes(20), "0", null, null),
            new StatusEvent(BASE_TIME.plusMinutes(25), "42", "F30005", null));
        return result(ReportHealthStatus.FAULT, DiagnosisStatus.FAULT_DETECTED, operation,
            List.of("F30005"), List.of(), events, List.of(candidate),
            List.of("检查负载"), List.of("仅依据故障码"), false, List.of(evidence));
    }

    /** 仅报警且已恢复：窗口末尾事件已无代码。 */
    private static OperationReportResult alarmRecoveredResult() {
        return alarmResult(List.of(
            new StatusEvent(BASE_TIME.plusMinutes(30), "42", null, "A07089"),
            new StatusEvent(BASE_TIME.plusMinutes(32), "0", null, null)));
    }

    /** 仅报警且持续中：窗口末尾事件仍携带 A07089。 */
    private static OperationReportResult alarmOngoingResult() {
        return alarmResult(List.of(
            new StatusEvent(BASE_TIME.plusMinutes(30), "0", null, null),
            new StatusEvent(BASE_TIME.plusMinutes(32), "42", null, "A07089")));
    }

    private static OperationReportResult alarmResult(List<StatusEvent> events) {
        LocalDateTime first = BASE_TIME.plusMinutes(30);
        OperationStatistics operation = new OperationStatistics(null, null, List.of(),
            List.of(new CodeOccurrence("A07089", 30, first, first.plusMinutes(2))));
        CandidateFault candidate = new CandidateFault("A07089", FaultCodeType.ALARM,
            KnowledgeLookupStatus.MATCHED,
            List.of(new FaultKnowledgeEvidence(7L, "doc", "G120故障手册", "fragment", 0, "直流回路电压异常")),
            List.of("EV-003"));
        return result(ReportHealthStatus.ATTENTION, DiagnosisStatus.WARNING_DETECTED, operation,
            List.of(), List.of("A07089"), events, List.of(candidate),
            List.of("检查供电电压"), List.of(), false, List.of());
    }

    private static List<StatusEvent> normalEvents() {
        return List.of(new StatusEvent(BASE_TIME, "0", null, null));
    }

    private static OperationReportResult result(ReportHealthStatus health, DiagnosisStatus status,
                                                OperationStatistics operation, List<String> faultCodes,
                                                List<String> alarmCodes, List<StatusEvent> events,
                                                List<CandidateFault> candidates, List<String> recommendations,
                                                List<String> limitations, boolean fallback,
                                                List<EvidenceReference> evidence) {
        LocalDateTime start = BASE_TIME;
        LocalDateTime end = start.plusHours(1);
        TelemetryQueryResult telemetry = new TelemetryQueryResult("G120电机1", start, end,
            new DataQualitySummary(10, 10, 0, 0, 0, 1D, true), faultCodes, alarmCodes, List.of(), events,
            new TelemetryStatistics(10, 12.1, 25.2, 18.7, 42.1, 76.2, 58.3, 38.2, 63.1, 49.6, 104.3, 104.3),
            "sha256-digest", fallback, end.minusMinutes(1), List.of(), operation);
        DiagnosisResult diagnosis = new DiagnosisResult("request", status, false, "G120电机1", "INV-1",
            start, end, start, end, fallback, end.minusMinutes(1), null,
            new DataQualitySummary(10, 10, 0, 0, 0, 1D, true),
            new TelemetryStatistics(10, 12.1, 25.2, 18.7, 42.1, 76.2, 58.3, 38.2, 63.1, 49.6, 104.3, 104.3),
            faultCodes, alarmCodes, List.of(), List.of(), candidates, recommendations, limitations, evidence);
        return OperationReportResult.fromSources("RP-1", "G120电机1", "INV-1", start, end,
            end.plusSeconds(30), health,
            new OperationReportResult.Summary("本周期设备状态：" + health.getDisplayName() + "。",
                faultCodes, alarmCodes, !fallback && status != DiagnosisStatus.DATA_INSUFFICIENT),
            telemetry, reportStatistics(telemetry), null, diagnosis);
    }

    private static TelemetryStatisticsResult reportStatistics(TelemetryQueryResult telemetry) {
        TelemetryStatistics statistics = telemetry.statistics();
        return new TelemetryStatisticsResult("G120电机1", "INV-1", telemetry.startTime(), telemetry.endTime(),
            statistics.sampleCount(), Map.of(
                "actualPower", values(statistics.avgActualPower(), statistics.minActualPower(),
                    statistics.maxActualPower(), statistics.sampleCount()),
                "motorTemp", values(statistics.avgMotorTemp(), statistics.minMotorTemp(),
                    statistics.maxMotorTemp(), statistics.sampleCount()),
                "inverterTemp", values(statistics.avgInverterTemp(), statistics.minInverterTemp(),
                    statistics.maxInverterTemp(), statistics.sampleCount()),
                "motorLoadRate", values(null, null, statistics.maxMotorLoadRate(), statistics.sampleCount()),
                "inverterLoadRate", values(null, null, statistics.maxInverterLoadRate(), statistics.sampleCount())),
            telemetry.quality());
    }

    private static Map<String, Number> values(Double average, Double minimum, Double maximum, int count) {
        java.util.LinkedHashMap<String, Number> values = new java.util.LinkedHashMap<>();
        if (average != null) {
            values.put("avg", average);
        }
        if (minimum != null) {
            values.put("min", minimum);
        }
        if (maximum != null) {
            values.put("max", maximum);
        }
        values.put("count", count);
        return Map.copyOf(values);
    }

}
