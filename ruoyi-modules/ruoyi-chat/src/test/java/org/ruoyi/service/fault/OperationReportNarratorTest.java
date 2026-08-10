package org.ruoyi.service.fault;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import org.ruoyi.service.fault.model.FaultExecutionResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证运行报告叙事层：成功返回受校验正文，任何失败路径确定性降级为 null。 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class OperationReportNarratorTest {

    @Mock
    private FaultAnswerSafetyValidator faultAnswerSafetyValidator;
    @InjectMocks
    private OperationReportNarrator narrator;

    @Test
    void returnsValidatedNarrative() {
        ChatModel model = model("A07089 为报警码，资料解释为直流回路电压异常[EV-001]。建议检查供电电压。");
        when(faultAnswerSafetyValidator.valid(anyString(), any(FaultExecutionResult.class), anyBoolean()))
            .thenReturn(true);

        String narrative = narrator.narrate(report(), model, null, "生成运行报告");

        assertEquals("A07089 为报警码，资料解释为直流回路电压异常[EV-001]。建议检查供电电压。", narrative);
    }

    @Test
    void nullModelFallsBackToNull() {
        assertNull(narrator.narrate(report(), null, null, null));
    }

    @Test
    void modelExceptionFallsBackToNull() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenThrow(new RuntimeException("boom"));

        assertNull(narrator.narrate(report(), model, null, null));
    }

    @Test
    void safetyRejectionFallsBackToNull() {
        ChatModel model = model("检测到未观测的 F99999 故障。");
        when(faultAnswerSafetyValidator.valid(anyString(), any(FaultExecutionResult.class), anyBoolean()))
            .thenReturn(false);

        assertNull(narrator.narrate(report(), model, null, null));
    }

    private static ChatModel model(String text) {
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from(text));
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(List.class))).thenReturn(response);
        return model;
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
