package org.ruoyi.service.fault;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.report.OperationReportResult;
import org.ruoyi.fault.report.ReportHealthStatus;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.OperationStatistics;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetryStatistics;
import org.ruoyi.service.chat.hermes.HermesChatClient;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesChatException;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesChatResult;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesMessage;

import java.time.LocalDateTime;
import java.util.List;

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
    void repairsUnknownFieldOnceThenAccepts() {
        when(hermesChatClient.complete(anyList())).thenReturn(result("{\"executiveSummary\":\"周期状态需关注\",\"extra\":true}"),
            result(arrayJson("周期状态需关注", false)));

        assertEquals("周期状态需关注", narrator().narrate(report()).executiveSummary());
        verify(hermesChatClient, times(2)).complete(anyList());
    }

    @Test
    void rejectsUnsupportedCodeAfterSingleRepair() {
        when(hermesChatClient.complete(anyList())).thenReturn(result(arrayJson("检查 F99999", false)));
        assertNull(narrator().narrate(report()));
        verify(hermesChatClient, times(2)).complete(anyList());
    }

    private OperationReportNarrator narrator() {
        return new OperationReportNarrator(hermesChatClient, new ObjectMapper());
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

    private static OperationReportResult report() {
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
            List.of(), List.of("A07089"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        return OperationReportResult.fromSources("RP-1", "设备A", "逆变器A", start, end, end.plusSeconds(30),
            ReportHealthStatus.ATTENTION,
            new OperationReportResult.Summary("报告周期内设备状态：关注。", List.of(), List.of("A07089"), true),
            telemetry, null, null, diagnosis);
    }
}
