package org.ruoyi.service.fault;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.evidence.enums.EvidenceType;
import org.ruoyi.fault.report.OperationReportAnalysisService;
import org.ruoyi.fault.report.DiagnosisSummary;
import org.ruoyi.fault.report.OperationReportResult;
import org.ruoyi.fault.report.ReportHealthStatus;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.OperationStatistics;
import org.ruoyi.fault.telemetry.model.ReportTelemetrySample;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetryStatistics;
import org.ruoyi.service.chat.hermes.HermesChatClient;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesChatException;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesChatResult;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesMessage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

/** 覆盖 Hermes 成功、不可用、非法代码/证据和 JSON 解析降级。 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class OperationReportNarratorTest {
    @Mock private HermesChatClient hermesChatClient;

    @Test
    void acceptsOneHermesJsonNarrative() {
        when(hermesChatClient.complete(anyList())).thenReturn(result(json("周期状态需关注", "无额外运行解读", "建议按报告事件排查", "P2", "核查相关回路", "报告事件", "请结合数据质量使用")));
        OperationReportNarrator narrator = narrator();

        OperationReportResult.ReportNarrative narrative = narrator.narrate(report());

        assertEquals("周期状态需关注", narrative.executiveSummary());
        assertEquals("P2", narrative.recommendations().get(0).priority());
        ArgumentCaptor<List<HermesMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(hermesChatClient).complete(messages.capture());
        assertEquals(2, messages.getValue().size());
        assertTrue(messages.getValue().get(0).content().contains("REPORT_NARRATION"));
    }

    @Test
    void hermesUnavailableFallsBackToDeterministicReport() {
        when(hermesChatClient.complete(anyList())).thenThrow(new HermesChatException("unavailable"));
        assertNull(narrator().narrate(report()));
    }

    @Test
    void rejectsUnknownCodeOrEvidence() {
        when(hermesChatClient.complete(anyList())).thenReturn(result(json("F99999", null, null, "P1", "检查", "EV-999", null)));
        assertNull(narrator().narrate(report()));
    }

    @Test
    void rejectsUnknownEvidenceWhenTheCodeIsAllowed() {
        when(hermesChatClient.complete(anyList())).thenReturn(result(json("A07089", null, null, "P1", "检查", "EV-999", null)));
        assertNull(narrator().narrate(report()));
    }

    @Test
    void rejectsMalformedJson() {
        when(hermesChatClient.complete(anyList())).thenReturn(result("not-json"));
        assertNull(narrator().narrate(report()));
    }

    @Test
    void acceptsFencedThinkJsonWithArrayBasisAndParagraphNumber() {
        String response = "<think>internal reasoning</think>\\n```json\\n" + arrayJson("第1项：周期内需关注", true) + "\\n```";
        when(hermesChatClient.complete(anyList())).thenReturn(result(response));

        OperationReportResult.ReportNarrative narrative = narrator().narrate(report());

        assertEquals(List.of("运行解读第1项"), narrative.operatingFindings());
        assertEquals(List.of("报告事件"), narrative.recommendations().get(0).basis());
        verify(hermesChatClient, times(1)).complete(anyList());
    }

    @Test
    void acceptsMissingOptionalFields() {
        when(hermesChatClient.complete(anyList())).thenReturn(result("{\"executiveSummary\":\"周期状态需关注\"}"));
        assertEquals("周期状态需关注", narrator().narrate(report()).executiveSummary());
    }

    @Test
    void acceptsProvidedDataQualityPercentButRejectsTelemetryUnits() {
        when(hermesChatClient.complete(anyList())).thenReturn(result(
            "{\"executiveSummary\":\"数据完整率 100.0%，可用于本次诊断。\"}"));
        assertEquals("数据完整率 100.0%，可用于本次诊断。", narrator().narrate(report()).executiveSummary());

        when(hermesChatClient.complete(anyList())).thenReturn(result(
            "{\"executiveSummary\":\"电流达到 20 A，需要关注。\"}"));
        assertNull(narrator().narrate(report()));
    }

    @Test
    void acceptsOnlyTelemetryMeasurementsWhitelistedByAnalysisFacts() {
        OperationReportResult report = report().withAnalysisFacts(new OperationReportResult.AnalysisFacts(List.of(
            new OperationReportResult.MetricAnalysis("motorTemp", "℃", 37.2D, 37.67D, 0.47D,
                37.97D, 36D, 39.99D, 3.99D, 0.8D)), List.of()));
        when(hermesChatClient.complete(anyList())).thenReturn(result(
            "{\"executiveSummary\":\"电机温度周期平均值为 37.970 ℃。\"}"));

        assertEquals("电机温度周期平均值为 37.970 ℃。", narrator().narrate(report).executiveSummary());

        when(hermesChatClient.complete(anyList())).thenReturn(result(
            "{\"executiveSummary\":\"电机温度达到 83.6 ℃。\"}"));
        assertNull(narrator().narrate(report));

        when(hermesChatClient.complete(anyList())).thenReturn(result(
            "{\"executiveSummary\":\"电机温度周期平均值为 37.970 V。\"}"));
        assertNull(narrator().narrate(report));
    }

    @Test
    void acceptsFactsRoundedToThePrecisionWrittenByHermes() {
        OperationReportResult report = report().withAnalysisFacts(new OperationReportResult.AnalysisFacts(List.of(
            new OperationReportResult.MetricAnalysis("speedActual", "r/min", 789.136D, 789.136D, 0D,
                789.136D, 789.136D, 789.136D, 0D, 0D)), List.of()));
        for (String value : List.of("789", "789.1", "789.14", "789.136")) {
            when(hermesChatClient.complete(anyList())).thenReturn(result(
                "{\"executiveSummary\":\"实际转速约 " + value + " r/min。\"}"));
            assertEquals("实际转速约 " + value + " r/min。", narrator().narrate(report).executiveSummary());
        }

        when(hermesChatClient.complete(anyList())).thenReturn(result("{\"executiveSummary\":\"实际转速约 780 r/min。\"}"));
        assertNull(narrator().narrate(report));

        when(hermesChatClient.complete(anyList())).thenReturn(result("{\"executiveSummary\":\"实际转速约 789.1 V。\"}"));
        assertNull(narrator().narrate(report));
    }

    @Test
    void finalizeAcceptsManualParametersFrozenInThePreparedSnapshot() {
        OperationReportResult report = withTechnicalTokens(report(), List.of("p0100", "r0052"));
        OperationReportResult.ReportNarrative narrative = new OperationReportResult.ReportNarrative(
            "建议依据手册核对参数。", List.of(), List.of(),
            List.of(new OperationReportResult.NarrativeRecommendation("P2", "核对单位制参数",
                List.of("报告诊断"), List.of("记录 p0100 与 r0052 的当前值"))), null);

        narrator().validateNarrative(report, narrative);
    }

    @Test
    void repairsUnknownFieldOnceThenAccepts() {
        when(hermesChatClient.complete(anyList())).thenReturn(result("{\"executiveSummary\":\"周期状态需关注\",\"extra\":true}"),
            result(arrayJson("周期状态需关注", false)));

        assertEquals("周期状态需关注", narrator().narrate(report()).executiveSummary());
        verify(hermesChatClient, times(2)).complete(anyList());
    }

    @Test
    void acceptsEventComparisonFactsIncludingBackendDerivedDeltas() {
        OperationReportResult report = eventComparisonReport();
        String finding = "A07089 报警期间平均电流为 0.64 A，较事件前的 0.58 A 升高 0.06 A；恢复后平均值为 0.59 A。";
        when(hermesChatClient.complete(anyList())).thenReturn(result("{\"operatingFindings\":[\"" + finding + "\"]}"));

        OperationReportResult.ReportNarrative narrative = narrator().narrate(report);

        assertEquals(List.of(finding), narrative.operatingFindings());
    }

    @Test
    void acceptsTheMagnitudeOfNegativeBackendDeltaWithoutAllowingOtherMeasurements() {
        OperationReportResult report = eventComparisonReport();
        String finding = "A07089 恢复后平均电流为 0.59 A，较报警期间的 0.64 A 降低 0.05 A。";
        when(hermesChatClient.complete(anyList())).thenReturn(result("{\"operatingFindings\":[\"" + finding + "\"]}"));

        OperationReportResult.ReportNarrative narrative = narrator().narrate(report);

        assertEquals(List.of(finding), narrative.operatingFindings());
    }

    @Test
    void rejectsDigitsThatDoNotEqualTheFactAtTheirWrittenPrecision() {
        OperationReportResult report = preciseEventComparisonReport();
        String finding = "A07089 报警期间平均电流为 0.641 A，较事件前 0.58 A 升高 0.0614 A。";
        when(hermesChatClient.complete(anyList())).thenReturn(result("{\"operatingFindings\":[\"" + finding + "\"]}"));

        assertNull(narrator().narrate(report));
    }

    @Test
    void repairsUnsupportedDerivedMeasurementUsingTrustedFacts() {
        OperationReportResult report = eventComparisonReport();
        String invalid = "{\"operatingFindings\":[\"A07089 报警期间平均电流较事件前升高 18 A。\"]}";
        String repaired = "{\"operatingFindings\":[\"A07089 报警期间平均电流为 0.64 A，较事件前 0.58 A 升高 0.06 A。\"]}";
        when(hermesChatClient.complete(anyList())).thenReturn(result(invalid), result(repaired));

        OperationReportResult.ReportNarrative narrative = narrator().narrate(report);

        assertTrue(narrative != null);
        assertEquals(1, narrative.operatingFindings().size());
        ArgumentCaptor<List<HermesMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(hermesChatClient, times(2)).complete(messages.capture());
        String repairInput = messages.getAllValues().get(1).get(1).content();
        assertTrue(repairInput.contains("\"rejectionReason\":\"UNSUPPORTED_NUMBER\""));
        assertTrue(repairInput.contains("\"facts\""));
        assertTrue(repairInput.contains("duringMinusBeforeAvg"));
    }

    @Test
    void acceptsNarrativeWithOnlyOperatingFindings() {
        when(hermesChatClient.complete(anyList())).thenReturn(result(
            "{\"operatingFindings\":[\"A07089 已恢复。\"]}"));

        OperationReportResult.ReportNarrative narrative = narrator().narrate(report());

        assertEquals(List.of("A07089 已恢复。"), narrative.operatingFindings());
    }

    @Test
    void repairsMismatchedEvidenceTypeUsingTheBoundEvidenceFact() {
        OperationReportResult report = reportWithEvidence();
        String invalid = "{\"anomalyAnalysis\":[\"依据 EV-003 · KNOWLEDGE，报警未升级为故障。\"]}";
        String repaired = "{\"anomalyAnalysis\":[\"依据 EV-003 · RULE_RESULT，报警未升级为故障。\"]}";
        when(hermesChatClient.complete(anyList())).thenReturn(result(invalid), result(repaired));

        OperationReportResult.ReportNarrative narrative = narrator().narrate(report);

        assertEquals(List.of("依据 EV-003 · RULE_RESULT，报警未升级为故障。"), narrative.anomalyAnalysis());
        ArgumentCaptor<List<HermesMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(hermesChatClient, times(2)).complete(messages.capture());
        assertTrue(messages.getAllValues().get(1).get(1).content().contains("evidenceType"));
        assertTrue(messages.getAllValues().get(1).get(1).content().contains("RULE_RESULT"));
    }

    @Test
    void repairsNormalRangeJudgmentWithoutBusinessThreshold() {
        String invalid = "{\"executiveSummary\":\"主要运行参数均记录在正常量程内。\"}";
        String repaired = "{\"executiveSummary\":\"主要运行参数的观测范围和周期均值见运行解读。\"}";
        when(hermesChatClient.complete(anyList())).thenReturn(result(invalid), result(repaired));

        assertEquals("主要运行参数的观测范围和周期均值见运行解读。", narrator().narrate(report()).executiveSummary());
    }

    @Test
    void acceptsTelemetryInterpretationForReportSizedSnapshot() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 13, 19, 8, 51);
        LocalDateTime end = start.plusHours(1);
        List<ReportTelemetrySample> samples = new ArrayList<>();
        for (int index = 0; index < 900; index++) {
            double current = index < 330 ? 0.58D : index < 810 ? 0.64D : 0.59D;
            samples.add(new ReportTelemetrySample(start.plusSeconds(index * 4L), Map.of(
                "dcVoltage", 556.9D,
                "currentActual", current,
                "speedActual", 800D,
                "actualPower", 0.047D,
                "inverterLoadRate", 38.8D,
                "inverterTemp", 29D,
                "motorLoadRate", 55D,
                "motorTemp", 38D)));
        }
        OperationReportResult.Event event = new OperationReportResult.Event("A07089", FaultCodeType.ALARM, false,
            start.plusMinutes(22), start.plusMinutes(54), start.plusMinutes(54), 480);
        OperationReportResult.AnalysisFacts facts = new OperationReportAnalysisService().analyze(
            new OperationReportResult.Period(start, end, start, end, false, end.minusSeconds(4), "digest"),
            List.of(
                new OperationReportResult.Metric("dcVoltage", 556.9D, 556.9D, 556.9D, 556.9D, 900, null),
                new OperationReportResult.Metric("currentActual", 0.59D, 0.613D, 0.58D, 0.64D, 900, null),
                new OperationReportResult.Metric("speedActual", 800D, 800D, 800D, 800D, 900, null),
                new OperationReportResult.Metric("actualPower", 0.047D, 0.047D, 0.047D, 0.047D, 900, null),
                new OperationReportResult.Metric("inverterLoadRate", 38.8D, 38.8D, 38.8D, 38.8D, 900, null),
                new OperationReportResult.Metric("inverterTemp", 29D, 29D, 29D, 29D, 900, null),
                new OperationReportResult.Metric("motorLoadRate", 55D, 55D, 55D, 55D, 900, null),
                new OperationReportResult.Metric("motorTemp", 38D, 38D, 38D, 38D, 900, null)),
            Map.of("dcVoltage", "V", "currentActual", "A", "speedActual", "r/min", "actualPower", "kW",
                "inverterLoadRate", "%", "inverterTemp", "℃", "motorLoadRate", "%", "motorTemp", "℃"),
            List.of(event), samples);
        OperationReportResult report = report().withAnalysisFacts(facts);
        String finding = "A07089 报警期间平均电流为 0.64 A，事件前为 0.58 A，后端计算差值为 0.06 A；恢复后平均值为 0.59 A。";
        when(hermesChatClient.complete(anyList())).thenReturn(result("{\"executiveSummary\":\"数据完整率 100%。\","
            + "\"operatingFindings\":[\"" + finding + "\"],\"anomalyAnalysis\":[\"现有数据不足以确认因果关系。\"]}"));

        OperationReportResult.ReportNarrative narrative = narrator().narrate(report);

        assertTrue(narrative != null);
        assertEquals(8, facts.metricAnalyses().size());
        assertEquals("数据完整率 100%。", narrative.executiveSummary());
        assertEquals(List.of(finding), narrative.operatingFindings());
        assertTrue(!narrative.anomalyAnalysis().isEmpty());
    }

    @Test
    void rejectsUnsupportedCodeAfterSingleRepair() {
        when(hermesChatClient.complete(anyList())).thenReturn(result(arrayJson("检查 F99999", false)));
        assertNull(narrator().narrate(report()));
        verify(hermesChatClient, times(2)).complete(anyList());
    }

    private OperationReportNarrator narrator() {
        return new OperationReportNarrator(hermesChatClient, new ObjectMapper().findAndRegisterModules());
    }

    private static HermesChatResult result(String body) { return new HermesChatResult(body); }

    private static String json(String summary, String findings, String analysis, String priority, String action,
                               String basis, String risk) {
        return "{\"executiveSummary\":" + quoted(summary) + ",\"operatingFindings\":" + quoted(findings)
            + ",\"anomalyAnalysis\":" + quoted(analysis) + ",\"recommendations\":[{\"priority\":\""
            + priority + "\",\"action\":\"" + action + "\",\"basis\":\"" + basis
            + "\"}],\"riskNotice\":" + quoted(risk) + "}";
    }

    private static String quoted(String value) { return value == null ? "null" : "\"" + value + "\""; }

    private static String arrayJson(String summary, boolean withOrdinal) {
        return "{\"executiveSummary\":\"" + summary + "\",\"operatingFindings\":[\"运行解读"
            + (withOrdinal ? "第1项" : "") + "\"],\"anomalyAnalysis\":[\"根据 A07089 做排查方向\"],"
            + "\"recommendations\":[{\"priority\":\"P2\",\"action\":\"核查相关回路\",\"basis\":[\"报告事件\"]}]}";
    }

    private static OperationReportResult eventComparisonReport() {
        OperationReportResult.IntervalMetricStats before = new OperationReportResult.IntervalMetricStats(true, 0.58D,
            0.5D, 0.65D, 10);
        OperationReportResult.IntervalMetricStats during = new OperationReportResult.IntervalMetricStats(true, 0.64D,
            0.55D, 0.7D, 10);
        OperationReportResult.IntervalMetricStats after = new OperationReportResult.IntervalMetricStats(true, 0.59D,
            0.52D, 0.66D, 10);
        OperationReportResult.EventMetricComparison comparison = new OperationReportResult.EventMetricComparison(before,
            during, after, 0.06D, -0.05D, 0.01D);
        OperationReportResult.EventComparison event = new OperationReportResult.EventComparison("A07089", FaultCodeType.ALARM,
            LocalDateTime.of(2026, 8, 4, 18, 0), LocalDateTime.of(2026, 8, 4, 18, 32), Map.of("currentActual", comparison));
        return report().withAnalysisFacts(new OperationReportResult.AnalysisFacts(List.of(
            new OperationReportResult.MetricAnalysis("currentActual", "A", 0.58D, 0.59D, 0.01D,
                0.6D, 0.5D, 0.7D, 0.2D, 0.04D)), List.of(event)));
    }

    private static OperationReportResult preciseEventComparisonReport() {
        OperationReportResult.IntervalMetricStats before = new OperationReportResult.IntervalMetricStats(true, 0.58D,
            0.5D, 0.65D, 10);
        OperationReportResult.IntervalMetricStats during = new OperationReportResult.IntervalMetricStats(true, 0.641D,
            0.55D, 0.7D, 10);
        OperationReportResult.IntervalMetricStats after = new OperationReportResult.IntervalMetricStats(true, 0.59D,
            0.52D, 0.66D, 10);
        OperationReportResult.EventMetricComparison comparison = new OperationReportResult.EventMetricComparison(before,
            during, after, 0.061D, -0.051D, 0.01D);
        OperationReportResult.EventComparison event = new OperationReportResult.EventComparison("A07089", FaultCodeType.ALARM,
            LocalDateTime.of(2026, 8, 4, 18, 0), LocalDateTime.of(2026, 8, 4, 18, 32), Map.of("currentActual", comparison));
        return report().withAnalysisFacts(new OperationReportResult.AnalysisFacts(List.of(
            new OperationReportResult.MetricAnalysis("currentActual", "A", 0.58D, 0.59D, 0.01D,
                0.6D, 0.5D, 0.7D, 0.2D, 0.04D)), List.of(event)));
    }

    private static OperationReportResult withTechnicalTokens(OperationReportResult report, List<String> tokens) {
        DiagnosisSummary source = report.diagnosis();
        DiagnosisSummary diagnosis = new DiagnosisSummary(source.status(), source.faultCodes(), source.alarmCodes(),
            source.unknownCodes(), source.partial(), source.decisionRationale(), source.codeKnowledge(), tokens);
        return new OperationReportResult(report.metadata(), report.asset(), report.period(), report.periodStatus(),
            report.currentStatus(), report.summary(), report.dataQuality(), report.metricUnits(), report.dataCompleteness(),
            report.metrics(), report.trends(), report.events(), report.statusTimeline(), report.analysisFacts(), diagnosis,
            report.recommendations(), report.evidence(), report.narrative(), report.limitations(), report.diagnosisDetail());
    }

    private static OperationReportResult report() {
        return report(List.of());
    }

    private static OperationReportResult reportWithEvidence() {
        return report(List.of(
            new EvidenceReference(1L, "EV-001", EvidenceType.TELEMETRY, "遥测记录", "已查询遥测", true),
            new EvidenceReference(2L, "EV-002", EvidenceType.KNOWLEDGE, "手册资料", "已匹配手册", true),
            new EvidenceReference(3L, "EV-003", EvidenceType.RULE_RESULT, "判断规则", "规则已执行", true)));
    }

    private static OperationReportResult report(List<EvidenceReference> evidence) {
        LocalDateTime start = LocalDateTime.of(2026, 8, 4, 0, 0);
        LocalDateTime end = start.plusDays(1);
        TelemetryQueryResult telemetry = new TelemetryQueryResult("设备A", start, end,
            new DataQualitySummary(10, 10, 0, 0, 0, 1D, true), List.of("A07089"), List.of(), List.of(),
            List.of(), new TelemetryStatistics(10, null, null, null, null, null, null, null, null, null,
            null, null), "digest", false, end.minusMinutes(1), List.of(), OperationStatistics.empty());
        DiagnosisResult diagnosis = new DiagnosisResult("request", DiagnosisStatus.WARNING_DETECTED, false,
            "设备A", "逆变器A", start, end, start, end, false, end.minusMinutes(1), null,
            new DataQualitySummary(10, 10, 0, 0, 0, 1D, true),
            new TelemetryStatistics(10, null, null, null, null, null, null, null, null, null, null, null),
            List.of(), List.of("A07089"), List.of(), List.of(), List.of(), List.of(), List.of(), evidence);
        return OperationReportResult.fromSources("RP-1", "设备A", "逆变器A", start, end, end.plusSeconds(30),
            ReportHealthStatus.ATTENTION,
            new OperationReportResult.Summary("报告周期内设备状态：关注。", List.of(), List.of("A07089"), true),
            telemetry, null, null, diagnosis);
    }
}
