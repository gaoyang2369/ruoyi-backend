package org.ruoyi.fault.report;

import org.ruoyi.fault.telemetry.model.ReportTelemetrySample;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将报告快照中的数值采样和事件投影为可审计的运行分析事实。
 * <p>
 * 本组件不访问数据库，也不产生任何“正常”“异常”之类的解释性结论。
 */
@Service
public class OperationReportAnalysisService {

    public OperationReportResult.AnalysisFacts analyze(OperationReportResult.Period period,
                                                        List<OperationReportResult.Metric> metrics,
                                                        Map<String, String> metricUnits,
                                                        List<OperationReportResult.Event> events,
                                                        List<ReportTelemetrySample> samples) {
        List<ReportTelemetrySample> source = samples == null ? List.of() : samples.stream()
            .filter(sample -> sample != null && sample.observedAt() != null)
            .sorted(Comparator.comparing(ReportTelemetrySample::observedAt)).toList();
        List<OperationReportResult.MetricAnalysis> metricAnalyses = metricAnalyses(metrics, metricUnits, source);
        List<OperationReportResult.EventComparison> eventComparisons = eventComparisons(period, metrics, events, source);
        return new OperationReportResult.AnalysisFacts(metricAnalyses, eventComparisons);
    }

    private List<OperationReportResult.MetricAnalysis> metricAnalyses(List<OperationReportResult.Metric> metrics,
                                                                        Map<String, String> units,
                                                                        List<ReportTelemetrySample> samples) {
        if (metrics == null || metrics.isEmpty()) {
            return List.of();
        }
        List<OperationReportResult.MetricAnalysis> result = new ArrayList<>();
        for (OperationReportResult.Metric metric : metrics) {
            List<Double> values = values(samples, metric.metricName(), null, null);
            Double start = values.isEmpty() ? null : round(values.get(0));
            Double end = values.isEmpty() ? null : round(values.get(values.size() - 1));
            Double minimum = metric.minimum();
            Double maximum = metric.maximum();
            result.add(new OperationReportResult.MetricAnalysis(metric.metricName(),
                units == null ? null : units.get(metric.metricName()), start, end, subtract(end, start),
                metric.average(), minimum, maximum, subtract(maximum, minimum), stdDev(values)));
        }
        return List.copyOf(result);
    }

    private List<OperationReportResult.EventComparison> eventComparisons(OperationReportResult.Period period,
                                                                           List<OperationReportResult.Metric> metrics,
                                                                           List<OperationReportResult.Event> events,
                                                                           List<ReportTelemetrySample> samples) {
        if (period == null || metrics == null || metrics.isEmpty() || events == null || events.isEmpty()) {
            return List.of();
        }
        LocalDateTime reportStart = period.analysisWindowStart() == null ? period.windowStart() : period.analysisWindowStart();
        LocalDateTime reportEnd = period.analysisWindowEnd() == null ? period.windowEnd() : period.analysisWindowEnd();
        if (reportStart == null || reportEnd == null) {
            return List.of();
        }
        List<OperationReportResult.EventComparison> result = new ArrayList<>();
        for (OperationReportResult.Event event : events) {
            if (event == null || event.firstSeenAt() == null) {
                continue;
            }
            LocalDateTime start = clamp(event.firstSeenAt(), reportStart, reportEnd);
            LocalDateTime eventEnd = event.recoveredAt() == null ? reportEnd : clamp(event.recoveredAt(), start, reportEnd);
            Map<String, OperationReportResult.EventMetricComparison> comparisons = new LinkedHashMap<>();
            for (OperationReportResult.Metric metric : metrics) {
                comparisons.put(metric.metricName(), new OperationReportResult.EventMetricComparison(
                    interval(samples, metric.metricName(), reportStart, start),
                    interval(samples, metric.metricName(), start, eventEnd),
                    event.recoveredAt() == null ? OperationReportResult.IntervalMetricStats.unavailable()
                        : interval(samples, metric.metricName(), eventEnd, reportEnd)));
            }
            result.add(new OperationReportResult.EventComparison(event.code(), event.type(), start,
                event.recoveredAt(), comparisons));
        }
        return List.copyOf(result);
    }

    /** 区间采用左闭右开，恢复时刻属于 after，避免同一采样同时计入两个区间。 */
    private OperationReportResult.IntervalMetricStats interval(List<ReportTelemetrySample> samples, String metric,
                                                                 LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            return OperationReportResult.IntervalMetricStats.unavailable();
        }
        List<Double> values = values(samples, metric, start, end);
        if (values.isEmpty()) {
            return OperationReportResult.IntervalMetricStats.unavailable();
        }
        return new OperationReportResult.IntervalMetricStats(true, round(average(values)), round(minimum(values)),
            round(maximum(values)), values.size());
    }

    private static List<Double> values(List<ReportTelemetrySample> samples, String metric,
                                       LocalDateTime start, LocalDateTime end) {
        List<Double> result = new ArrayList<>();
        for (ReportTelemetrySample sample : samples) {
            if (start != null && sample.observedAt().isBefore(start)) continue;
            if (end != null && !sample.observedAt().isBefore(end)) continue;
            Double value = sample.metrics().get(metric);
            if (value != null) result.add(value);
        }
        return result;
    }

    private static LocalDateTime clamp(LocalDateTime value, LocalDateTime start, LocalDateTime end) {
        if (value.isBefore(start)) return start;
        return value.isAfter(end) ? end : value;
    }

    private static Double subtract(Double left, Double right) {
        return left == null || right == null ? null : round(left - right);
    }

    private static Double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static Double minimum(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
    }

    private static Double maximum(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
    }

    /** 总体标准差；分析对象是报告同一快照中的所有有效采样，而非 Hermes 侧重新计算。 */
    private static Double stdDev(List<Double> values) {
        if (values.isEmpty()) return null;
        double average = average(values);
        double variance = values.stream().mapToDouble(value -> {
            double delta = value - average;
            return delta * delta;
        }).average().orElseThrow();
        return round(Math.sqrt(variance));
    }

    private static double round(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
