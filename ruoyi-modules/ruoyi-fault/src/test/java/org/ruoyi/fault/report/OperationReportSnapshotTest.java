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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 运行报告全文快照测试。
 * <p>
 * 覆盖六类报告状态（故障、仅报警、正常、数据不足、历史回退、故障与报警同时存在），
 * 精简版逐字钉住首屏结论、卡片、建议与边界表述；完整版另钉住时间线翻译、
 * 数据质量、中文证据链与报告元数据。golden 文件位于
 * src/test/resources/report-snapshots/，渲染器输出变化时必须人工审查后更新。
 */
@Tag("dev")
class OperationReportSnapshotTest {

    private static final LocalDateTime REQUEST_START = LocalDateTime.of(2026, 8, 4, 15, 15, 54);
    private static final LocalDateTime REQUEST_END = LocalDateTime.of(2026, 8, 4, 15, 45, 54);
    private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 8, 4, 15, 46, 24);
    private static final Map<String, String> NO_UNITS = Map.of();

    @Test
    void faultOngoingConciseSnapshot() {
        assertEquals(golden("fault-ongoing-concise.md"),
            MarkdownOperationReportRenderer.renderConcise(faultOngoing(), null, NO_UNITS));
    }

    @Test
    void alarmOnlyRecoveredConciseSnapshot() {
        assertEquals(golden("alarm-only-recovered-concise.md"),
            MarkdownOperationReportRenderer.renderConcise(alarmOnlyRecovered(), null, NO_UNITS));
    }

    @Test
    void normalConciseSnapshot() {
        assertEquals(golden("normal-concise.md"),
            MarkdownOperationReportRenderer.renderConcise(normal(), null, NO_UNITS));
    }

    @Test
    void dataInsufficientConciseSnapshot() {
        assertEquals(golden("data-insufficient-concise.md"),
            MarkdownOperationReportRenderer.renderConcise(dataInsufficient(), null, NO_UNITS));
    }

    @Test
    void historyFallbackConciseSnapshot() {
        assertEquals(golden("history-fallback-concise.md"),
            MarkdownOperationReportRenderer.renderConcise(historyFallback(), null, NO_UNITS));
    }

    @Test
    void faultAndAlarmConciseSnapshot() {
        assertEquals(golden("fault-and-alarm-concise.md"),
            MarkdownOperationReportRenderer.renderConcise(faultAndAlarm(), null, NO_UNITS));
    }

    @Test
    void faultAndAlarmFullSnapshot() {
        assertEquals(golden("fault-and-alarm-full.md"),
            MarkdownOperationReportRenderer.renderFull(faultAndAlarm(), null, NO_UNITS));
    }

    // ---------- 场景构造 ----------

    /** 故障持续中：窗口末尾事件仍携带 F30899。 */
    static OperationReportResult faultOngoing() {
        LocalDateTime first = LocalDateTime.of(2026, 8, 4, 15, 30, 0);
        OperationStatistics operation = new OperationStatistics(null, null,
            List.of(new CodeOccurrence("F30899", 30, first, first.plusSeconds(116))), List.of());
        TelemetryQueryResult telemetry = telemetry(List.of("F30899"), List.of(),
            List.of(new StatusEvent(REQUEST_START, "0", null, null),
                new StatusEvent(first, "42", "F30899", null)),
            operation, false, REQUEST_START, REQUEST_END);
        return report(ReportHealthStatus.FAULT, DiagnosisStatus.FAULT_DETECTED,
            "报告周期内设备状态：故障。检测到故障码 F30899。数据完整率 100.0%（有效样本 450 条）。",
            telemetry, List.of(candidate("F30899", FaultCodeType.FAULT)),
            List.of("F30899"), List.of(),
            List.of("按手册核对 F30899 的可能原因与处理步骤。", "故障未恢复前避免强制复位，先排除负载与供电异常。"),
            List.of(), List.of());
    }

    /** 仅报警且已恢复：复刻 A07089 截图场景，窗口末尾事件已无代码。 */
    static OperationReportResult alarmOnlyRecovered() {
        LocalDateTime first = LocalDateTime.of(2026, 8, 4, 15, 30, 0);
        LocalDateTime last = LocalDateTime.of(2026, 8, 4, 15, 31, 56);
        OperationStatistics operation = new OperationStatistics(null, null, List.of(),
            List.of(new CodeOccurrence("A07089", 30, first, last)));
        TelemetryQueryResult telemetry = telemetry(List.of(), List.of("A07089"),
            List.of(new StatusEvent(REQUEST_START, "0", null, null),
                new StatusEvent(first, "42", null, "A07089"),
                new StatusEvent(LocalDateTime.of(2026, 8, 4, 15, 32, 0), "0", null, null)),
            operation, false, REQUEST_START, REQUEST_END);
        return report(ReportHealthStatus.ATTENTION, DiagnosisStatus.WARNING_DETECTED,
            "报告周期内设备状态：关注。存在报警码 A07089。数据完整率 100.0%（有效样本 450 条）。",
            telemetry, List.of(candidate("A07089", FaultCodeType.ALARM)),
            List.of(), List.of("A07089"),
            List.of("确认近期是否执行过单位切换或功能模块激活。",
                "按手册核对相关单位设置，修改前备份参数。",
                "修改后重新采集数据，确认报警不再出现。"),
            List.of(), List.of());
    }

    /** 正常：窗口内未发现显式代码。 */
    static OperationReportResult normal() {
        TelemetryQueryResult telemetry = telemetry(List.of(), List.of(),
            List.of(new StatusEvent(REQUEST_START, "0", null, null)),
            OperationStatistics.empty(), false, REQUEST_START, REQUEST_END);
        return report(ReportHealthStatus.NORMAL, DiagnosisStatus.NO_EXPLICIT_FAULT,
            "报告周期内设备状态：正常。窗口内未发现显式故障码或报警码。数据完整率 100.0%（有效样本 450 条）。",
            telemetry, List.of(), List.of(), List.of(),
            List.of("保持常规巡检，无需额外处理。"), List.of(), List.of());
    }

    /** 数据不足：质量不达标，无法给出确定性结论。 */
    static OperationReportResult dataInsufficient() {
        TelemetryQueryResult telemetry = telemetry(List.of(), List.of(), List.of(),
            OperationStatistics.empty(), false, REQUEST_START, REQUEST_END,
            new DataQualitySummary(40, 3, 0, 2, 30, 0.2D, false));
        return report(ReportHealthStatus.UNKNOWN, DiagnosisStatus.DATA_INSUFFICIENT,
            "报告周期内设备状态：未知。无数据或数据质量不足，无法确认设备状态。数据完整率 20.0%（有效样本 3 条）。",
            telemetry, List.of(), List.of(), List.of(),
            List.of("检查数据采集链路与网络状态，补齐数据后重新生成报告。"),
            List.of("数据完整率低于阈值，本次不给出诊断结论"), List.of());
    }

    /** 历史回退：请求窗口无数据，使用最近可用的历史正常窗口。 */
    static OperationReportResult historyFallback() {
        LocalDateTime historyStart = LocalDateTime.of(2026, 7, 19, 14, 50, 1);
        LocalDateTime historyEnd = LocalDateTime.of(2026, 7, 19, 15, 4, 11);
        TelemetryQueryResult telemetry = telemetry(List.of(), List.of(),
            List.of(new StatusEvent(historyStart, "0", null, null)),
            OperationStatistics.empty(), true, historyStart, historyEnd);
        return report(ReportHealthStatus.NORMAL, DiagnosisStatus.NO_EXPLICIT_FAULT,
            "报告周期内设备状态：正常。窗口内未发现显式故障码或报警码。数据完整率 100.0%（有效样本 215 条）。"
                + "请求窗口无数据，已改用最近可用数据窗口，本结果为历史数据分析"
                + "（最新数据时间 2026-07-19 15:04:11）。",
            telemetry, List.of(), List.of(), List.of(),
            List.of("保持常规巡检，无需额外处理。"), List.of(), List.of());
    }

    /** 故障与报警同时存在：故障持续中，报警已恢复。 */
    static OperationReportResult faultAndAlarm() {
        LocalDateTime first = LocalDateTime.of(2026, 8, 4, 15, 30, 0);
        OperationStatistics operation = new OperationStatistics(null, null,
            List.of(new CodeOccurrence("F30899", 30, first, first.plusSeconds(116))),
            List.of(new CodeOccurrence("A07089", 28, first, first.plusSeconds(112))));
        TelemetryQueryResult telemetry = telemetry(List.of("F30899"), List.of("A07089"),
            List.of(new StatusEvent(REQUEST_START, "0", null, null),
                new StatusEvent(first, "42", "F30899", "A07089"),
                new StatusEvent(LocalDateTime.of(2026, 8, 4, 15, 32, 0), "42", "F30899", null)),
            operation, false, REQUEST_START, REQUEST_END);
        return report(ReportHealthStatus.FAULT, DiagnosisStatus.FAULT_DETECTED,
            "报告周期内设备状态：故障。检测到故障码 F30899。数据完整率 100.0%（有效样本 450 条）。",
            telemetry,
            List.of(candidate("F30899", FaultCodeType.FAULT), candidate("A07089", FaultCodeType.ALARM)),
            List.of("F30899"), List.of("A07089"),
            List.of("按手册核对 F30899 的可能原因与处理步骤。",
                "报警 A07089 已恢复，记录触发时的工况供后续比对。",
                "故障未恢复前避免强制复位，先排除负载与供电异常。"),
            List.of("知识库内容仅为资料解释，不能替代对本设备实际参数的核对。"),
            List.of(
                new EvidenceReference(1L, "EV-001", EvidenceType.TELEMETRY, "遥测记录",
                    "G120电机1，2026-08-04 15:15:54—2026-08-04 15:45:54，共450条有效记录，出现 F30899、A07089",
                    true),
                new EvidenceReference(2L, "EV-002", EvidenceType.KNOWLEDGE, "手册资料",
                    "《G120故障手册》F30899 条目，代码类型为故障", true),
                new EvidenceReference(3L, "EV-003", EvidenceType.KNOWLEDGE, "手册资料",
                    "《G120故障手册》A07089 条目，代码类型为报警", true),
                new EvidenceReference(4L, "EV-004", EvidenceType.RULE_RESULT, "判断规则",
                    "A 类代码归入报警，F 类代码归入故障，未知格式不升级为故障；报警不升级为故障结论", true)));
    }

    // ---------- 通用构造 ----------

    private static TelemetryQueryResult telemetry(List<String> faultCodes, List<String> alarmCodes,
                                                  List<StatusEvent> events, OperationStatistics operation,
                                                  boolean fallback, LocalDateTime windowStart,
                                                  LocalDateTime windowEnd) {
        return telemetry(faultCodes, alarmCodes, events, operation, fallback, windowStart, windowEnd,
            new DataQualitySummary(450, 450, 0, 0, 0, 1D, true));
    }

    private static TelemetryQueryResult telemetry(List<String> faultCodes, List<String> alarmCodes,
                                                  List<StatusEvent> events, OperationStatistics operation,
                                                  boolean fallback, LocalDateTime windowStart,
                                                  LocalDateTime windowEnd, DataQualitySummary quality) {
        return new TelemetryQueryResult("G120电机1", windowStart, windowEnd,
            quality, faultCodes, alarmCodes, List.of(), events,
            new TelemetryStatistics(450, 0.01, 0.05, 0.03, 36.5, 39.8, 37.99, 27.9, 30.4, 29.0, 45.6, 64.99),
            "sha256:b7f2a9c0d4e5816f3a2c9d0e1f4b5a6c7d8e9f0a1b2c3d4e5f60718293a4b5c6",
            fallback, windowEnd.minusSeconds(4), List.of(), operation);
    }

    private static CandidateFault candidate(String code, FaultCodeType type) {
        return new CandidateFault(code, type, KnowledgeLookupStatus.MATCHED,
            List.of(new FaultKnowledgeEvidence(7L, "doc-1", "G120故障手册", "fragment-1", 0, "手册条目内容")),
            List.of("EV-002"));
    }

    private static OperationReportResult report(ReportHealthStatus health, DiagnosisStatus status, String summary,
                                                TelemetryQueryResult telemetry, List<CandidateFault> candidates,
                                                List<String> faultCodes, List<String> alarmCodes,
                                                List<String> recommendations, List<String> limitations,
                                                List<EvidenceReference> evidence) {
        DiagnosisResult diagnosis = new DiagnosisResult("request-1", status, false, "G120电机1", "INV-1",
            REQUEST_START, REQUEST_END, telemetry.startTime(), telemetry.endTime(),
            telemetry.fallbackToLatestData(), telemetry.latestObservedAt(), null,
            telemetry.quality(), telemetry.statistics(), faultCodes, alarmCodes, List.of(), List.of(),
            candidates, recommendations, limitations, evidence);
        return new OperationReportResult("RP-1", "G120电机1", "INV-1", REQUEST_START, REQUEST_END,
            GENERATED_AT, health, summary, telemetry, diagnosis);
    }

    private static String golden(String name) {
        try (InputStream in = OperationReportSnapshotTest.class.getResourceAsStream("/report-snapshots/" + name)) {
            assertNotNull(in, "缺少 golden 文件: report-snapshots/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
