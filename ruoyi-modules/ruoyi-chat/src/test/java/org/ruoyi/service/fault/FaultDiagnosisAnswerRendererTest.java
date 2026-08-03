package org.ruoyi.service.fault;

import org.junit.jupiter.api.Test;
import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.evidence.enums.EvidenceType;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.service.fault.model.FaultExecutionResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证统一回答骨架的确定性渲染：时态、故障/报警分列、可读证据和内部字段隔离。
 */
class FaultDiagnosisAnswerRendererTest {

    private static final LocalDateTime REQUEST_START = LocalDateTime.of(2026, 8, 3, 9, 0);
    private static final LocalDateTime REQUEST_END = LocalDateTime.of(2026, 8, 3, 9, 30);
    private static final LocalDateTime DATA_START = LocalDateTime.of(2026, 7, 19, 14, 50, 1);
    private static final LocalDateTime DATA_END = LocalDateTime.of(2026, 7, 19, 15, 4, 12);
    private static final LocalDateTime LATEST_OBSERVED = LocalDateTime.of(2026, 7, 19, 15, 4, 11);

    @Test
    void fallbackResultStartsWithCurrentStatusUnknownAndDataAnchor() {
        DiagnosisResult result = resultBuilder()
            .status(DiagnosisStatus.WARNING_DETECTED)
            .fallback(true)
            .alarmCodes(List.of("A07089"))
            .build();

        String answer = render(result);

        assertTrue(answer.startsWith("## 结论\n\n当前状态无法确认。"));
        assertTrue(answer.contains("设备最新数据时间为 2026-07-19 15:04:11，属于历史数据"));
        assertFalse(answer.contains("当前检测到故障"));
        assertTrue(answer.contains("- 报警：A07089"));
    }

    @Test
    void nonFallbackResultUsesAnchoredConclusion() {
        DiagnosisResult result = resultBuilder()
            .status(DiagnosisStatus.NO_EXPLICIT_FAULT)
            .build();

        String answer = render(result);

        assertTrue(answer.contains("未发现显式故障码或报警码。"));
        assertTrue(answer.contains("截至 2026-07-19 15:04:11"));
    }

    @Test
    void faultAndAlarmAreShownSeparatelyWhenBothExist() {
        DiagnosisResult result = resultBuilder()
            .status(DiagnosisStatus.FAULT_DETECTED)
            .faultCodes(List.of("F30899"))
            .alarmCodes(List.of("A07089"))
            .build();

        String answer = render(result);

        assertTrue(answer.contains("检测到 F30899 故障，同时存在 A07089 报警。"));
        assertTrue(answer.contains("- 故障：F30899"));
        assertTrue(answer.contains("- 报警：A07089"));
    }

    @Test
    void unknownCodesAreShownWithoutBeingEscalated() {
        DiagnosisResult result = resultBuilder()
            .status(DiagnosisStatus.NO_EXPLICIT_FAULT)
            .unknownCodes(List.of("XYZ-1"))
            .build();

        String answer = render(result);

        assertTrue(answer.contains("- 未识别代码：XYZ-1（未升级为故障）"));
    }

    @Test
    void evidenceIsRenderedInOrderWithReadableSummaries() {
        EvidenceReference telemetry = new EvidenceReference(1L, "EV-001", EvidenceType.TELEMETRY, "遥测记录",
            "G120电机1，2026-07-19 14:50:01—15:04:11，共245条有效记录，出现 A07089", true);
        EvidenceReference knowledge = new EvidenceReference(2L, "EV-002", EvidenceType.KNOWLEDGE, "手册资料",
            "《G120故障手册》A07089 条目，代码类型为报警", true);
        EvidenceReference rules = new EvidenceReference(3L, "EV-003", EvidenceType.RULE_RESULT, "判断规则",
            "A 类代码归入报警；历史回退数据不能表示当前状态", true);
        EvidenceReference internal = new EvidenceReference(4L, "EV-004", EvidenceType.RULE_RESULT, "结果记录",
            "诊断结果组装完成", false);
        DiagnosisResult result = resultBuilder()
            .status(DiagnosisStatus.WARNING_DETECTED)
            .alarmCodes(List.of("A07089"))
            .evidence(List.of(telemetry, knowledge, rules, internal))
            .build();

        String answer = render(result);

        int telemetryIndex = answer.indexOf("1. 遥测记录（EV-001）：G120电机1");
        int knowledgeIndex = answer.indexOf("2. 手册资料（EV-002）：《G120故障手册》");
        int rulesIndex = answer.indexOf("3. 判断规则（EV-003）");
        assertTrue(telemetryIndex >= 0);
        assertTrue(knowledgeIndex > telemetryIndex);
        assertTrue(rulesIndex > knowledgeIndex);
        assertFalse(answer.contains("EV-004"));
    }

    @Test
    void deterministicMiddleUsesAlarmTermAndNumberedRecommendations() {
        DiagnosisResult result = resultBuilder()
            .status(DiagnosisStatus.WARNING_DETECTED)
            .alarmCodes(List.of("A07089"))
            .candidates(List.of(new CandidateFault("A07089", FaultCodeType.ALARM, KnowledgeLookupStatus.MATCHED,
                List.of(new FaultKnowledgeEvidence(1L, "doc-1", "G120故障手册", "fragment-1", 1, "长知识片段")),
                List.of("EV-002"))))
            .recommendations(List.of("核对 c0100、p0505 当前值", "确认近期是否执行过单位切换"))
            .build();

        String answer = render(result);

        assertTrue(answer.contains("## 代码说明"));
        assertTrue(answer.contains("A07089 是报警码，已匹配手册资料：G120故障手册"));
        assertTrue(answer.contains("不代表本设备已确认根因"));
        assertFalse(answer.contains("长知识片段"));
        assertTrue(answer.contains("## 建议"));
        assertTrue(answer.contains("1. 核对 c0100、p0505 当前值"));
        assertTrue(answer.contains("2. 确认近期是否执行过单位切换"));
    }

    @Test
    void answerDoesNotExposeInternalFields() {
        DiagnosisResult result = resultBuilder()
            .status(DiagnosisStatus.FAULT_DETECTED)
            .faultCodes(List.of("F30899"))
            .partial(true)
            .build();

        String answer = render(result);

        assertFalse(answer.contains("request-1"));
        assertFalse(answer.contains("FAULT_DETECTED"));
        assertFalse(answer.contains("partial"));
        assertTrue(answer.contains("本次结果为降级结果"));
        assertTrue(answer.contains("数据质量详情："));
    }

    @Test
    void modelBodyWithoutHeadingsIsWrapped() {
        DiagnosisResult result = resultBuilder().status(DiagnosisStatus.NO_EXPLICIT_FAULT).build();
        FaultExecutionResult execution = new FaultExecutionResult(null, result, Map.of(), result.limitations());

        String answer = FaultDiagnosisAnswerRenderer.render(execution, "普通说明文字");

        assertTrue(answer.contains("## 代码说明与建议\n\n普通说明文字"));
    }

    private static String render(DiagnosisResult result) {
        FaultExecutionResult execution = new FaultExecutionResult(null, result, Map.of(), result.limitations());
        return FaultDiagnosisAnswerRenderer.render(execution, null);
    }

    private static ResultBuilder resultBuilder() {
        return new ResultBuilder();
    }

    private static final class ResultBuilder {
        private DiagnosisStatus status = DiagnosisStatus.NO_EXPLICIT_FAULT;
        private boolean fallback = false;
        private boolean partial = false;
        private List<String> faultCodes = List.of();
        private List<String> alarmCodes = List.of();
        private List<String> unknownCodes = List.of();
        private List<CandidateFault> candidates = List.of();
        private List<EvidenceReference> evidence = List.of();
        private List<String> recommendations = List.of();

        ResultBuilder status(DiagnosisStatus value) { this.status = value; return this; }
        ResultBuilder fallback(boolean value) { this.fallback = value; return this; }
        ResultBuilder partial(boolean value) { this.partial = value; return this; }
        ResultBuilder faultCodes(List<String> value) { this.faultCodes = value; return this; }
        ResultBuilder alarmCodes(List<String> value) { this.alarmCodes = value; return this; }
        ResultBuilder unknownCodes(List<String> value) { this.unknownCodes = value; return this; }
        ResultBuilder candidates(List<CandidateFault> value) { this.candidates = value; return this; }
        ResultBuilder evidence(List<EvidenceReference> value) { this.evidence = value; return this; }
        ResultBuilder recommendations(List<String> value) { this.recommendations = value; return this; }

        DiagnosisResult build() {
            LocalDateTime latestObservedAt = fallback ? LATEST_OBSERVED : LATEST_OBSERVED;
            LocalDateTime analysisStart = fallback ? DATA_START : REQUEST_START;
            LocalDateTime analysisEnd = fallback ? DATA_END : REQUEST_END;
            List<String> limitations = fallback
                ? List.of("请求时间范围内没有遥测数据，已回退至该设备最近可用数据") : List.of();
            return new DiagnosisResult("request-1", status, partial, "G120电机1", "G120电机1",
                REQUEST_START, REQUEST_END, analysisStart, analysisEnd, fallback, latestObservedAt, "症状",
                new DataQualitySummary(245, 245, 0, 0, 0, 1D, true), null,
                faultCodes, alarmCodes, unknownCodes, List.of(), candidates, recommendations, limitations,
                evidence);
        }
    }

    @Test
    void builderProducesRequestedWindows() {
        DiagnosisResult result = resultBuilder().build();
        assertEquals(REQUEST_START, result.requestedStartTime());
        assertEquals(REQUEST_END, result.requestedEndTime());
    }
}
