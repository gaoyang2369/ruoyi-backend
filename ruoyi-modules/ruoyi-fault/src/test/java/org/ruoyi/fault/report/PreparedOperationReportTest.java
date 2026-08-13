package org.ruoyi.fault.report;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dev")
class PreparedOperationReportTest {

    @Test
    void exposesStableMetricMetadataAlongsideComputedFacts() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 13, 10, 0);
        OperationReportResult base = OperationReportResult.fromSources("RP-META", "设备A", "INV-A", start,
            start.plusMinutes(30), start.plusMinutes(31), ReportHealthStatus.NORMAL,
            new OperationReportResult.Summary("正常", List.of(), List.of(), true), null, null, null, null);
        OperationReportResult report = new OperationReportResult(base.metadata(), base.asset(), base.period(),
            base.periodStatus(), base.currentStatus(), base.summary(), base.dataQuality(),
            Map.of("speedActual", "r/min"), base.dataCompleteness(),
            List.of(new OperationReportResult.Metric("speedActual", 789.136D, 790D, 780D, 800D, 10, null)),
            base.trends(), base.events(), base.statusTimeline(), base.diagnosis(), base.recommendations(),
            base.evidence(), null, base.limitations(), base.diagnosisDetail());

        PreparedOperationReport.MetricFact metric = PreparedOperationReport.from(report).metrics().get(0);

        assertEquals("speedActual", metric.key());
        assertEquals("实际转速", metric.displayName());
        assertEquals("r/min", metric.unit());
        assertEquals(789.136D, metric.current());
    }
}
