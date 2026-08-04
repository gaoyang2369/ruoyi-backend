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

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class MarkdownOperationReportRendererTest {

    @Test
    void rendersAllTenSectionsForFaultReport() {
        String markdown = MarkdownOperationReportRenderer.render(faultResult());

        for (int section = 1; section <= 10; section++) {
            assertTrue(markdown.contains("## " + section + ". "), "缺少章节 " + section);
        }
        assertTrue(markdown.contains("RP-1"));
        assertTrue(markdown.contains("G120电机1"));
        assertTrue(markdown.contains("故障"));
        assertTrue(markdown.contains("F30005"));
        assertTrue(markdown.contains("已匹配"));
        assertTrue(markdown.contains("EV-001"));
        assertTrue(markdown.contains("sha256-digest"));
    }

    @Test
    void rendersCodesAndEvidenceAsBulletListsForNarrowBubbles() {
        String markdown = MarkdownOperationReportRenderer.render(faultResult());

        assertTrue(markdown.contains("- F30005（故障码）：出现 3 次，首次 2026-08-04 10:05:00，最近 2026-08-04 10:07:00；知识匹配：已匹配：G120故障手册"));
        assertTrue(markdown.contains("- EV-001 TELEMETRY：遥测记录，窗口内 10 条有效数据"));
        assertFalse(markdown.contains("| 代码 | 类型 |"));
        assertFalse(markdown.contains("| 编号 | 类型 |"));
    }

    @Test
    void rendersNarrativeInSectionEightWhenProvided() {
        String markdown = MarkdownOperationReportRenderer.render(faultResult(),
            "F30005 与电机过载相关[EV-002]，建议检查负载。");

        assertTrue(markdown.contains("## 8. 代码说明与处理建议"));
        assertTrue(markdown.contains("F30005 与电机过载相关[EV-002]，建议检查负载。"));
        assertFalse(markdown.contains("暂无针对本周期的处理建议"));
    }

    @Test
    void rendersNormalReportWithoutCodes() {
        String markdown = MarkdownOperationReportRenderer.render(result(ReportHealthStatus.NORMAL,
            DiagnosisStatus.NO_EXPLICIT_FAULT, OperationStatistics.empty(), List.of(), List.of(),
            List.of(), List.of(), List.of(), false));

        assertTrue(markdown.contains("本周期未发现故障码或报警码。"));
        assertTrue(markdown.contains("未发现显式故障码或报警码。"));
    }

    @Test
    void rendersDataInsufficientDisclaimer() {
        String markdown = MarkdownOperationReportRenderer.render(result(ReportHealthStatus.UNKNOWN,
            DiagnosisStatus.DATA_INSUFFICIENT, OperationStatistics.empty(), List.of(), List.of(),
            List.of(), List.of(), List.of(), false));

        assertTrue(markdown.contains("数据不足，本周期无法给出确定性诊断结论。"));
    }

    @Test
    void rendersFallbackCalloutInMetaSection() {
        String markdown = MarkdownOperationReportRenderer.render(result(ReportHealthStatus.NORMAL,
            DiagnosisStatus.NO_EXPLICIT_FAULT, OperationStatistics.empty(), List.of(), List.of(),
            List.of(), List.of(), List.of(), true));

        assertTrue(markdown.contains("已回退到最近可用数据窗口"));
    }

    @Test
    void rendersEmptyRecommendationsAndLimitationsPlaceholders() {
        String markdown = MarkdownOperationReportRenderer.render(result(ReportHealthStatus.NORMAL,
            DiagnosisStatus.NO_EXPLICIT_FAULT, OperationStatistics.empty(), List.of(), List.of(),
            List.of(), List.of(), List.of(), false));

        assertTrue(markdown.contains("暂无针对本周期的处理建议"));
        assertTrue(markdown.contains("- 无"));
    }

    @Test
    void omitsNoEvidenceWhenIndexEmpty() {
        String markdown = MarkdownOperationReportRenderer.render(result(ReportHealthStatus.NORMAL,
            DiagnosisStatus.NO_EXPLICIT_FAULT, OperationStatistics.empty(), List.of(), List.of(),
            List.of(), List.of(), List.of(), false));

        assertTrue(markdown.contains("本次没有可引用的持久化证据。"));
        assertFalse(markdown.contains("EV-001"));
    }

    private static OperationReportResult faultResult() {
        LocalDateTime first = LocalDateTime.of(2026, 8, 4, 10, 5);
        OperationStatistics operation = new OperationStatistics(first.plusMinutes(10), first.plusMinutes(12),
            List.of(new CodeOccurrence("F30005", 3, first, first.plusMinutes(2))), List.of());
        List<CodeOccurrence> faults = operation.faultCodeOccurrences();
        CandidateFault candidate = new CandidateFault("F30005", FaultCodeType.FAULT,
            KnowledgeLookupStatus.MATCHED,
            List.of(new FaultKnowledgeEvidence(7L, "doc", "G120故障手册", "fragment", 0, "电机过载")),
            List.of("EV-003"));
        EvidenceReference evidence = new EvidenceReference(1L, "EV-001", EvidenceType.TELEMETRY,
            "遥测记录", "窗口内 10 条有效数据", true);
        return result(ReportHealthStatus.FAULT, DiagnosisStatus.FAULT_DETECTED, operation,
            List.of("F30005"), List.of(new StatusEvent(first, "FAULT", "F30005", null)),
            List.of(candidate), List.of("检查负载"), List.of("仅依据故障码"), false, List.of(evidence), faults);
    }

    private static OperationReportResult result(ReportHealthStatus health, DiagnosisStatus status,
                                                OperationStatistics operation, List<String> faultCodes,
                                                List<StatusEvent> events, List<CandidateFault> candidates,
                                                List<String> recommendations, List<String> limitations,
                                                boolean fallback) {
        return result(health, status, operation, faultCodes, events, candidates, recommendations,
            limitations, fallback, List.of(), List.of());
    }

    private static OperationReportResult result(ReportHealthStatus health, DiagnosisStatus status,
                                                OperationStatistics operation, List<String> faultCodes,
                                                List<StatusEvent> events, List<CandidateFault> candidates,
                                                List<String> recommendations, List<String> limitations,
                                                boolean fallback, List<EvidenceReference> evidence,
                                                List<CodeOccurrence> faultOccurrences) {
        LocalDateTime start = LocalDateTime.of(2026, 8, 4, 0, 0);
        LocalDateTime end = start.plusDays(1);
        TelemetryQueryResult telemetry = new TelemetryQueryResult("G120电机1", start, end,
            new DataQualitySummary(10, 10, 0, 0, 0, 1D, true), faultCodes, List.of(), List.of(), events,
            new TelemetryStatistics(10, 12.1, 25.2, 18.7, 42.1, 76.2, 58.3, 38.2, 63.1, 49.6, 104.3, 104.3),
            "sha256-digest", fallback, end.minusMinutes(1), List.of(), operation);
        DiagnosisResult diagnosis = new DiagnosisResult("request", status, false, "G120电机1", "INV-1",
            start, end, start, end, fallback, end.minusMinutes(1), null,
            new DataQualitySummary(10, 10, 0, 0, 0, 1D, true),
            new TelemetryStatistics(10, 12.1, 25.2, 18.7, 42.1, 76.2, 58.3, 38.2, 63.1, 49.6, 104.3, 104.3),
            faultCodes, List.of(), List.of(), List.of(), candidates, recommendations, limitations, evidence);
        return new OperationReportResult("RP-1", "G120电机1", "INV-1", start, end,
            end.plusSeconds(30), health, "本周期设备状态：" + health.getDisplayName() + "。",
            telemetry, diagnosis);
    }

}
