package org.ruoyi.fault.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.telemetry.model.ReportTelemetrySample;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class OperationReportAnalysisServiceTest {

    private final OperationReportAnalysisService service = new OperationReportAnalysisService();
    private final LocalDateTime start = LocalDateTime.of(2026, 8, 10, 17, 0);
    private final LocalDateTime end = start.plusHours(3);

    @Test
    void calculatesAllNormalTrendFactsFromTheExistingSnapshot() {
        OperationReportResult.AnalysisFacts facts = analyze(List.of(), List.of(
            sample(0, 10D), sample(1, 12D), sample(2, 14D)));

        OperationReportResult.MetricAnalysis metric = facts.metricAnalyses().get(0);
        assertEquals(10D, metric.startValue());
        assertEquals(14D, metric.endValue());
        assertEquals(4D, metric.delta());
        assertEquals(12D, metric.avg());
        assertEquals(10D, metric.min());
        assertEquals(14D, metric.max());
        assertEquals(4D, metric.range());
        assertEquals(1.633D, metric.stdDev());
    }

    @Test
    void comparesBeforeDuringAndAfterForRecoveredAlarm() {
        LocalDateTime alarmStart = start.plusHours(1);
        LocalDateTime recovered = alarmStart.plusMinutes(32);
        OperationReportResult.AnalysisFacts facts = analyze(List.of(event("A07089", alarmStart, recovered)), List.of(
            sample(30, 30D), sample(60, 40D), sample(91, 50D), sample(92, 60D), sample(120, 35D)));

        OperationReportResult.EventMetricComparison comparison = facts.eventComparisons().get(0).metrics().get("motorTemp");
        assertEquals(30D, comparison.before().avg());
        assertEquals(45D, comparison.during().avg());
        assertEquals(47.5D, comparison.after().avg());
        assertEquals(1, comparison.before().sampleCount());
        assertEquals(2, comparison.during().sampleCount());
        assertEquals(2, comparison.after().sampleCount());
        assertEquals(15D, comparison.duringMinusBeforeAvg());
        assertEquals(2.5D, comparison.afterMinusDuringAvg());
        assertEquals(17.5D, comparison.afterMinusBeforeAvg());
    }

    @Test
    void keepsUnrecoveredEventOpenAndDoesNotInventAfterWindow() {
        LocalDateTime alarmStart = start.plusHours(1);
        OperationReportResult.AnalysisFacts facts = analyze(List.of(event("A07089", alarmStart, null)), List.of(
            sample(30, 30D), sample(60, 40D), sample(120, 50D)));

        OperationReportResult.EventComparison event = facts.eventComparisons().get(0);
        assertNull(event.endTime());
        assertEquals(30D, event.metrics().get("motorTemp").before().avg());
        assertEquals(45D, event.metrics().get("motorTemp").during().avg());
        assertFalse(event.metrics().get("motorTemp").after().available());
        assertEquals(15D, event.metrics().get("motorTemp").duringMinusBeforeAvg());
        assertNull(event.metrics().get("motorTemp").afterMinusDuringAvg());
        assertNull(event.metrics().get("motorTemp").afterMinusBeforeAvg());
    }

    @Test
    void comparesRepeatedCodeEpisodesWithoutIncludingRecoveredGapInDuring() {
        LocalDateTime firstStart = start.plusMinutes(60);
        LocalDateTime firstRecovered = firstStart.plusMinutes(2);
        LocalDateTime secondStart = start.plusMinutes(120);
        LocalDateTime secondRecovered = secondStart.plusMinutes(2);
        OperationReportResult.AnalysisFacts facts = analyze(List.of(
            event("A07089", firstStart, firstRecovered), event("A07089", secondStart, secondRecovered)), List.of(
            sample(30, 30D), sample(60, 40D), sample(61, 42D), sample(90, 10D),
            sample(120, 50D), sample(121, 52D), sample(150, 35D)));

        assertEquals(2, facts.eventComparisons().size());
        assertEquals(41D, facts.eventComparisons().get(0).metrics().get("motorTemp").during().avg());
        assertEquals(51D, facts.eventComparisons().get(1).metrics().get("motorTemp").during().avg());
    }

    @Test
    void marksIntervalsWithoutSamplesUnavailable() {
        OperationReportResult.AnalysisFacts facts = analyze(List.of(event("A07089", start.plusHours(1), start.plusHours(2))),
            List.of(sample(30, 30D)));

        OperationReportResult.EventMetricComparison comparison = facts.eventComparisons().get(0).metrics().get("motorTemp");
        assertTrue(comparison.before().available());
        assertFalse(comparison.during().available());
        assertFalse(comparison.after().available());
        assertEquals(0, comparison.during().sampleCount());
        assertNull(comparison.during().avg());
    }

    private OperationReportResult.AnalysisFacts analyze(List<OperationReportResult.Event> events,
                                                         List<ReportTelemetrySample> samples) {
        return service.analyze(new OperationReportResult.Period(start, end, start, end, false, end, "digest"),
            List.of(new OperationReportResult.Metric("motorTemp", samples.get(samples.size() - 1).metrics().get("motorTemp"),
                12D, 10D, 14D, samples.size(), null)), Map.of("motorTemp", "℃"), events, samples);
    }

    private ReportTelemetrySample sample(long minutes, double motorTemp) {
        return new ReportTelemetrySample(start.plusMinutes(minutes), Map.of("motorTemp", motorTemp));
    }

    private OperationReportResult.Event event(String code, LocalDateTime eventStart, LocalDateTime recoveredAt) {
        return new OperationReportResult.Event(code, FaultCodeType.ALARM, recoveredAt == null, eventStart,
            recoveredAt == null ? end.minusMinutes(1) : recoveredAt.minusMinutes(1), recoveredAt, 1);
    }
}
