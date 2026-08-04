package org.ruoyi.fault.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.ruoyi.fault.domain.code.FaultCodeType;
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
            new KnowledgeLookupAggregation(List.of(new CandidateFault("F1", FaultCodeType.FAULT,
                KnowledgeLookupStatus.FAILED, null, null)), List.of(), List.of()), decision(List.of(), List.of()));
        DiagnosisResult notFound = assembler.assemble(command(), telemetry(List.of("F1"), List.of()),
            new KnowledgeLookupAggregation(List.of(new CandidateFault("F1", FaultCodeType.FAULT,
                KnowledgeLookupStatus.NOT_FOUND, null, null)), List.of(), List.of()), decision(List.of(), List.of()));

        assertTrue(failed.partial());
        assertFalse(notFound.partial());
    }

    @Test
    void fallbackTelemetryUsesActualWindowAndAddsLimitation() {
        LocalDateTime fallbackStart = LocalDateTime.of(2026, 1, 14, 18, 0);
        TelemetryQueryResult fallback = new TelemetryQueryResult("device", fallbackStart, fallbackStart.plusMinutes(30),
            new DataQualitySummary(1, 1, 0, 0, 0, 1D, true), List.of(), List.of(), List.of(), null, null, null,
            true, fallbackStart.plusMinutes(29), List.of(), null);

        DiagnosisResult result = assembler.assemble(command(), fallback,
            new KnowledgeLookupAggregation(List.of(), List.of(), List.of()), decision(List.of(), List.of()));

        assertEquals(fallbackStart, result.startTime());
        assertEquals(fallbackStart.plusMinutes(30), result.endTime());
        assertTrue(result.fallbackToLatestData());
        assertEquals(fallbackStart.plusMinutes(29), result.latestObservedAt());
        assertTrue(result.limitations().stream().anyMatch(item -> item.contains("已回退至该设备最近可用数据")));
    }

    @Test
    void keepsRequestedWindowAndStructuredTimeBoundary() {
        LocalDateTime latestObservedAt = LocalDateTime.of(2026, 1, 1, 0, 4);
        DiagnosisResult result = assembler.assemble(command(),
            telemetry(List.of(), List.of(), latestObservedAt, List.of()),
            new KnowledgeLookupAggregation(List.of(), List.of(), List.of()), decision(List.of(), List.of()));

        assertEquals(command().startTime(), result.requestedStartTime());
        assertEquals(command().endTime(), result.requestedEndTime());
        assertEquals(result.startTime(), result.requestedStartTime());
        assertFalse(result.fallbackToLatestData());
        assertEquals(latestObservedAt, result.latestObservedAt());
    }

    @Test
    void codeNormalizationNotesBecomeLimitations() {
        DiagnosisResult result = assembler.assemble(command(),
            telemetry(List.of(), List.of("A07089"), null,
                List.of("fault_code 字段出现 A 类报警码 A07089，字段与代码类型不一致，已按 G120 规则归类为报警")),
            new KnowledgeLookupAggregation(List.of(), List.of(), List.of()), decision(List.of(), List.of()));

        assertTrue(result.limitations().stream()
            .anyMatch(item -> item.contains("字段与代码类型不一致")));
    }

    private static DiagnosisCommand command() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new DiagnosisCommand("device", "inverter", start, start.plusMinutes(5), null, List.of(1L),
            new DiagnosisRequestContext(1L, 2L, 3L, "tenant", "request"));
    }

    private static TelemetryQueryResult telemetry(List<String> faults, List<String> alarms) {
        return telemetry(faults, alarms, null, List.of());
    }

    private static TelemetryQueryResult telemetry(List<String> faults, List<String> alarms,
                                                  LocalDateTime latestObservedAt, List<String> notes) {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new TelemetryQueryResult("device", start, start.plusMinutes(5),
            new DataQualitySummary(1, 1, 0, 0, 0, 1D, true), faults, alarms, List.of(), null, null, null, false,
            latestObservedAt, notes, null);
    }

    private static DiagnosisDecision decision(List<String> recommendations, List<String> limitations) {
        return new DiagnosisDecision(DiagnosisStatus.NO_EXPLICIT_FAULT,
            List.of(new DiagnosisObservation("test", ObservationType.STATUS_EVENT, "test", null, null)),
            recommendations, limitations);
    }
}
