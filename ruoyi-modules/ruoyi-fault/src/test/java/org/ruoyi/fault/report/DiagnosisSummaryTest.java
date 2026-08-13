package org.ruoyi.fault.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.TelemetryStatistics;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dev")
class DiagnosisSummaryTest {

    @Test
    void freezesTechnicalParametersFoundInMatchedKnowledge() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 1, 0, 0);
        CandidateFault candidate = new CandidateFault("A07089", FaultCodeType.ALARM, KnowledgeLookupStatus.MATCHED,
            List.of(new FaultKnowledgeEvidence(1L, "manual", "manual.pdf", "fragment-1", 1,
                "检查 P0100；记录 r0052，再确认 p0100。")), List.of("EV-001"));
        DiagnosisResult diagnosis = new DiagnosisResult("request", DiagnosisStatus.WARNING_DETECTED, false,
            "device", "inverter", start, start.plusMinutes(30), start, start.plusMinutes(30), false, start,
            null, new DataQualitySummary(1, 1, 0, 0, 0, 1D, true),
            new TelemetryStatistics(1, null, null, null, null, null, null, null, null, null, null, null),
            List.of(), List.of("A07089"), List.of(), List.of(), List.of(candidate), List.of(), List.of(), List.of());

        assertEquals(List.of("p0100", "r0052"), DiagnosisSummary.from(diagnosis).allowedTechnicalTokens());
    }
}
