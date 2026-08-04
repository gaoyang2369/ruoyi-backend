package org.ruoyi.service.fault;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.service.fault.model.FaultExecutionResult;
import org.ruoyi.service.fault.model.FaultRequestPlan;
import org.ruoyi.service.fault.model.FaultTaskType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class FaultAnswerSafetyValidatorTest {
    private final FaultAnswerSafetyValidator validator = new FaultAnswerSafetyValidator(new EvidenceCitationValidator());
    private final FaultExecutionResult queriedOnly = new FaultExecutionResult(
        new FaultRequestPlan(List.of(FaultTaskType.EXPLAIN_FAULT_CODE), null, null, null, null, null,
            List.of("F30005"), null, List.of()), null, Map.of(), List.of());

    @Test void rejectsQueriedOnlyCodeClaimedAsObserved() {
        assertFalse(validator.valid("本次检测到 F30005。", queriedOnly, false));
    }

    @Test void permitsExplicitNegationAndKnowledgeExplanation() {
        assertTrue(validator.valid("本次未检测到 F30005；这里仅说明其知识含义。", queriedOnly, false));
    }

    @Test void rejectsTelemetryNarrativeWhenDiagnosisWasNotExecuted() {
        assertFalse(validator.valid("本次遥测实际观测到的故障码为空，因此仅说明 F30005。", queriedOnly, false));
    }

    @Test void rejectsInventedFaultCodeAndConfidence() {
        assertFalse(validator.valid("F99999 的故障概率为 0.9", queriedOnly, false));
        assertFalse(validator.valid("F30005 的置信度 80%", queriedOnly, false));
    }

    @Test void rejectsInventedAlarmCode() {
        assertFalse(validator.valid("本次检测到报警 A99999。", queriedOnly, false));
    }

    @Test void permitsObservedAlarmCodeInDiagnosisAnswer() {
        assertTrue(validator.valid("存在报警 A07089 [EV-1]。", diagnosisWithAlarm(), true));
    }

    @Test void rejectsObservedClaimForQueriedOnlyAlarmCode() {
        assertFalse(validator.valid("本次检测到 A07089。", queriedAlarmOnly(), false));
    }

    @Test void permitsTechnicalTokenPresentInKnowledgeEvidence() {
        assertTrue(validator.valid("建议核对 p0421 设置 [EV-1]。",
            diagnosisWithKnowledge("处理：核对 p0421 设置。"), true));
    }

    @Test void rejectsTechnicalTokenAbsentFromKnowledgeEvidence() {
        assertFalse(validator.valid("建议检查参数 p9999 [EV-1]。",
            diagnosisWithKnowledge("处理：核对 p0421 设置。"), true));
    }

    @Test void rejectsTechnicalTokenWhenNoKnowledgeEvidence() {
        assertFalse(validator.valid("建议检查参数 p0349 [EV-1]。", diagnosisWithAlarm(), true));
    }

    private static FaultExecutionResult diagnosisWithKnowledge(String fragmentContent) {
        DiagnosisResult result = new DiagnosisResult("request-1", DiagnosisStatus.WARNING_DETECTED, false,
            "设备A", "逆变器A",
            LocalDateTime.of(2026, 7, 19, 14, 50), LocalDateTime.of(2026, 7, 19, 15, 4),
            LocalDateTime.of(2026, 7, 19, 14, 50), LocalDateTime.of(2026, 7, 19, 15, 4),
            false, LocalDateTime.of(2026, 7, 19, 15, 4), "症状",
            new DataQualitySummary(10, 10, 0, 0, 0, 1D, true), null,
            List.of(), List.of("A07089"), List.of(), List.of(),
            List.of(new CandidateFault("A07089", FaultCodeType.ALARM, KnowledgeLookupStatus.MATCHED,
                List.of(new FaultKnowledgeEvidence(7L, "doc", "G120故障手册", "fragment", 0, fragmentContent)),
                List.of("EV-1"))),
            List.of(), List.of(),
            List.of(new EvidenceReference(1L, "EV-1")));
        return new FaultExecutionResult(null, result, Map.of(), result.limitations());
    }

    private static FaultExecutionResult diagnosisWithAlarm() {
        DiagnosisResult result = new DiagnosisResult("request-1", DiagnosisStatus.WARNING_DETECTED, false,
            "设备A", "逆变器A",
            LocalDateTime.of(2026, 7, 19, 14, 50), LocalDateTime.of(2026, 7, 19, 15, 4),
            LocalDateTime.of(2026, 7, 19, 14, 50), LocalDateTime.of(2026, 7, 19, 15, 4),
            false, LocalDateTime.of(2026, 7, 19, 15, 4), "症状",
            new DataQualitySummary(10, 10, 0, 0, 0, 1D, true), null,
            List.of(), List.of("A07089"), List.of(), List.of(),
            List.of(new CandidateFault("A07089", FaultCodeType.ALARM, KnowledgeLookupStatus.MATCHED,
                List.of(), List.of("EV-1"))),
            List.of(), List.of(),
            List.of(new EvidenceReference(1L, "EV-1")));
        return new FaultExecutionResult(null, result, Map.of(), result.limitations());
    }

    private static FaultExecutionResult queriedAlarmOnly() {
        return new FaultExecutionResult(
            new FaultRequestPlan(List.of(FaultTaskType.EXPLAIN_FAULT_CODE), null, null, null, null, null,
                List.of("A07089"), null, List.of()), null, Map.of(), List.of());
    }
}
