package org.ruoyi.fault.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.context.DiagnosisRequestContext;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.domain.enums.ObservationType;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisDecision;
import org.ruoyi.fault.domain.result.DiagnosisObservation;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class DiagnosisResultAssemblerTest {

    private final DiagnosisResultAssembler assembler = new DiagnosisResultAssembler();

    @Test
    void normalizesNullCollectionsAndReturnsImmutableCollections() {
        DiagnosisResult result = assembler.assemble(command(), telemetry(null, null),
            new KnowledgeLookupAggregation(null, null, null), decision(List.of(), List.of()));

        assertEquals(List.of(), result.faultCodes());
        assertEquals(List.of(), result.candidateFaults());
        assertThrows(UnsupportedOperationException.class, () -> result.limitations().add("x"));
    }

    @Test
    void deduplicatesLimitsAndRecommendationsInFirstSeenOrder() {
        DiagnosisResult result = assembler.assemble(command(), telemetry(List.of(), List.of()),
            new KnowledgeLookupAggregation(List.of(), List.of(), List.of("l2", "l1")),
            decision(List.of("r1", "r1", "r2"), List.of("l1", "l2")));

        assertEquals(List.of("r1", "r2"), result.recommendations());
        assertEquals(List.of("l1", "l2"), result.limitations());
    }

    @Test
    void onlyFailedKnowledgeLookupMakesResultPartial() {
        DiagnosisResult failed = assembler.assemble(command(), telemetry(List.of("F1"), List.of()),
            new KnowledgeLookupAggregation(List.of(new CandidateFault("F1", KnowledgeLookupStatus.FAILED, null, null)),
                List.of(), List.of()), decision(List.of(), List.of()));
        DiagnosisResult notFound = assembler.assemble(command(), telemetry(List.of("F1"), List.of()),
            new KnowledgeLookupAggregation(List.of(new CandidateFault("F1", KnowledgeLookupStatus.NOT_FOUND, null, null)),
                List.of(), List.of()), decision(List.of(), List.of()));

        assertTrue(failed.partial());
        assertFalse(notFound.partial());
    }

    @Test
    void fallbackTelemetryUsesActualWindowAndAddsLimitation() {
        LocalDateTime fallbackStart = LocalDateTime.of(2026, 1, 14, 18, 0);
        TelemetryQueryResult fallback = new TelemetryQueryResult("device", fallbackStart, fallbackStart.plusMinutes(30),
            new DataQualitySummary(1, 1, 0, 0, 0, 1D, true), List.of(), List.of(), null, null, null, true);

        DiagnosisResult result = assembler.assemble(command(), fallback,
            new KnowledgeLookupAggregation(List.of(), List.of(), List.of()), decision(List.of(), List.of()));

        assertEquals(fallbackStart, result.startTime());
        assertEquals(fallbackStart.plusMinutes(30), result.endTime());
        assertTrue(result.limitations().stream().anyMatch(item -> item.contains("已回退至该设备最近可用数据")));
    }

    private static DiagnosisCommand command() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new DiagnosisCommand("device", "inverter", start, start.plusMinutes(5), null, List.of(1L),
            new DiagnosisRequestContext(1L, 2L, 3L, "tenant", "request"));
    }

    private static TelemetryQueryResult telemetry(List<String> faults, List<String> alarms) {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new TelemetryQueryResult("device", start, start.plusMinutes(5),
            new DataQualitySummary(1, 1, 0, 0, 0, 1D, true), faults, alarms, null, null, null, false);
    }

    private static DiagnosisDecision decision(List<String> recommendations, List<String> limitations) {
        return new DiagnosisDecision(DiagnosisStatus.NO_EXPLICIT_FAULT,
            List.of(new DiagnosisObservation("test", ObservationType.STATUS_EVENT, "test", null, null)),
            recommendations, limitations);
    }
}
