package org.ruoyi.fault.telemetry.analysis;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.telemetry.entity.RealDataEntity;
import org.ruoyi.fault.telemetry.model.CodeOccurrence;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.OperationStatistics;
import org.ruoyi.fault.telemetry.model.StatusEvent;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetryReportSnapshot;
import org.ruoyi.fault.telemetry.model.TelemetryStatistics;
import org.ruoyi.fault.telemetry.model.TelemetryStatisticsResult;
import org.ruoyi.fault.telemetry.model.TelemetrySeriesPoint;
import org.ruoyi.fault.telemetry.model.TelemetrySeriesResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 对 Mapper 返回的遥测行执行确定性分析。
 * <p>
 * 分析顺序固定为：解析业务时间、精确过滤、冲突去重、数据质量计算、事件与统计摘要、证据摘要。
 * 该组件不访问数据库、不调用 LLM，也不根据模型输出改变算法分支。
 */
@Component
@RequiredArgsConstructor
public class TelemetryDataAnalyzer {

    private final TelemetryObservedAtParser observedAtParser;
    private final FaultDiagnosisProperties properties;

    /**
     * 将数据库粗筛结果转换为可以安全交给诊断编排器的有限摘要。
     *
     * @param deviceName 已通过权限校验的设备名称
     * @param inverterName 逆变器名称
     * @param startTime 精确窗口起点（包含）
     * @param endTime 精确窗口终点（不包含）
     * @param rawRecords 数据库基于 create_time 粗筛得到的原始记录
     * @return 不包含原始时序列表的确定性分析结果
     */
    public TelemetryQueryResult analyze(String deviceName, String inverterName,
                                        LocalDateTime startTime, LocalDateTime endTime,
                                        List<RealDataEntity> rawRecords) {
        AnalysisData analysis = prepareAnalysis(rawRecords, startTime, endTime);
        return buildTelemetryResult(deviceName, inverterName, startTime, endTime, analysis);
    }

    /**
     * 从同一份已完成过滤和去重的遥测数据生成报告所需的摘要、通用统计与降采样趋势。
     * 该方法不读取数据库，也不会为 statistics 或 series 再次执行预处理。
     */
    public TelemetryReportSnapshot analyzeReport(String deviceName, String inverterName,
                                                 LocalDateTime startTime, LocalDateTime endTime,
                                                 List<RealDataEntity> rawRecords,
                                                 List<String> requestedMetrics, int bucketMinutes) {
        List<String> metrics = resolveMetrics(requestedMetrics);
        validateBucketMinutes(bucketMinutes);
        AnalysisData analysis = prepareAnalysis(rawRecords, startTime, endTime);
        TelemetryQueryResult telemetry = buildTelemetryResult(deviceName, inverterName, startTime, endTime, analysis);
        if (analysis.validRecords().isEmpty() || !analysis.quality().sufficient()) {
            return new TelemetryReportSnapshot(telemetry, null, null);
        }
        return new TelemetryReportSnapshot(telemetry,
            buildStatisticsResult(deviceName, inverterName, startTime, endTime, analysis, metrics,
                Set.of("avg", "min", "max", "count"), true),
            buildSeriesResult(deviceName, inverterName, startTime, endTime, analysis, metrics, bucketMinutes, true));
    }

    private TelemetryQueryResult buildTelemetryResult(String deviceName, String inverterName,
                                                       LocalDateTime startTime, LocalDateTime endTime,
                                                       AnalysisData analysis) {
        List<TimedRecord> validRecords = analysis.validRecords();
        DataQualitySummary quality = analysis.quality();
        CodeExtraction codes = extractClassifiedCodes(validRecords);
        List<StatusEvent> statusEvents = buildStatusEvents(validRecords);
        TelemetryStatistics statistics = buildStatistics(validRecords);
        OperationStatistics operation = buildOperationStatistics(validRecords);
        String sourceDigest = sourceDigest(deviceName, inverterName, startTime, endTime, analysis.rawRecords(), quality);
        LocalDateTime latestObservedAt = validRecords.isEmpty()
            ? null : validRecords.get(validRecords.size() - 1).observedAt();

        return new TelemetryQueryResult(deviceName, startTime, endTime, quality, codes.faultCodes(),
            codes.alarmCodes(), codes.unknownCodes(), statusEvents, statistics, sourceDigest, false,
            latestObservedAt, codes.notes(), operation);
    }

    /**
     * 计算指定数值指标的统计结果。其时间解析、精确过滤、去重和质量口径与 {@link #analyze} 完全一致。
     */
    public TelemetryStatisticsResult analyzeStatistics(String deviceName, String inverterName,
                                                        LocalDateTime startTime, LocalDateTime endTime,
                                                        List<RealDataEntity> rawRecords,
                                                        List<String> requestedMetrics,
                                                        List<String> requestedAggregations) {
        List<String> metrics = resolveMetrics(requestedMetrics);
        Set<String> aggregations = resolveAggregations(requestedAggregations);
        AnalysisData analysis = prepareAnalysis(rawRecords, startTime, endTime);
        if (analysis.validRecords().isEmpty()) {
            throw new ServiceException("查询窗口内没有有效遥测数据");
        }
        return buildStatisticsResult(deviceName, inverterName, startTime, endTime, analysis, metrics, aggregations, false);
    }

    /** 在读取数据库前校验统计请求，避免非法指标触发不必要的数据查询。 */
    public void validateStatisticsRequest(List<String> requestedMetrics, List<String> requestedAggregations) {
        resolveMetrics(requestedMetrics);
        resolveAggregations(requestedAggregations);
    }

    /**
     * 按查询窗口起点分桶并计算各桶平均值；每个桶均使用已精确过滤、确定性去重后的有效遥测记录。
     */
    public TelemetrySeriesResult analyzeSeries(String deviceName, String inverterName,
                                               LocalDateTime startTime, LocalDateTime endTime,
                                               List<RealDataEntity> rawRecords,
                                               List<String> requestedMetrics, int bucketMinutes) {
        List<String> metrics = resolveMetrics(requestedMetrics);
        validateBucketMinutes(bucketMinutes);
        AnalysisData analysis = prepareAnalysis(rawRecords, startTime, endTime);
        if (analysis.validRecords().isEmpty()) {
            throw new ServiceException("查询窗口内没有有效遥测数据");
        }
        return buildSeriesResult(deviceName, inverterName, startTime, endTime, analysis, metrics, bucketMinutes, false);
    }

    private TelemetryStatisticsResult buildStatisticsResult(String deviceName, String inverterName,
                                                            LocalDateTime startTime, LocalDateTime endTime,
                                                            AnalysisData analysis, List<String> metrics,
                                                            Set<String> aggregations, boolean skipEmptyMetrics) {
        Map<String, Map<String, Number>> results = new LinkedHashMap<>();
        for (String metric : metrics) {
            NumericSummary summary = summarize(analysis.validRecords(), metricExtractor(metric));
            if (summary.count() == 0) {
                if (skipEmptyMetrics) {
                    continue;
                }
                throw new ServiceException("指标 " + metric + " 在查询窗口内没有有效数据");
            }
            Map<String, Number> values = new LinkedHashMap<>();
            if (aggregations.contains("avg")) {
                values.put("avg", summary.average());
            }
            if (aggregations.contains("min")) {
                values.put("min", summary.min());
            }
            if (aggregations.contains("max")) {
                values.put("max", summary.max());
            }
            if (aggregations.contains("count")) {
                values.put("count", summary.count());
            }
            results.put(metric, Map.copyOf(values));
        }
        return new TelemetryStatisticsResult(deviceName, inverterName, startTime, endTime,
            analysis.validRecords().size(), Map.copyOf(results), analysis.quality());
    }

    private TelemetrySeriesResult buildSeriesResult(String deviceName, String inverterName,
                                                    LocalDateTime startTime, LocalDateTime endTime,
                                                    AnalysisData analysis, List<String> metrics, int bucketMinutes,
                                                    boolean skipEmptyMetrics) {
        Map<String, List<TelemetrySeriesPoint>> series = new LinkedHashMap<>();
        for (String metric : metrics) {
            Map<Long, SeriesBucketAccumulator> buckets = new LinkedHashMap<>();
            Function<RealDataEntity, Float> extractor = metricExtractor(metric);
            for (TimedRecord record : analysis.validRecords()) {
                Float value = extractor.apply(record.data());
                if (value == null) {
                    continue;
                }
                long bucketIndex = Duration.between(startTime, record.observedAt()).toSeconds()
                    / (bucketMinutes * 60L);
                buckets.computeIfAbsent(bucketIndex, ignored -> new SeriesBucketAccumulator()).accept(value);
            }
            if (buckets.isEmpty()) {
                if (skipEmptyMetrics) {
                    continue;
                }
                throw new ServiceException("指标 " + metric + " 在查询窗口内没有有效数据");
            }
            List<TelemetrySeriesPoint> points = new ArrayList<>();
            for (Map.Entry<Long, SeriesBucketAccumulator> entry : buckets.entrySet()) {
                SeriesBucketAccumulator bucket = entry.getValue();
                points.add(new TelemetrySeriesPoint(startTime.plusMinutes(entry.getKey() * (long) bucketMinutes),
                    round(bucket.average()), bucket.count()));
            }
            series.put(metric, List.copyOf(points));
        }
        return new TelemetrySeriesResult(deviceName, inverterName, startTime, endTime, bucketMinutes,
            analysis.validRecords().size(), Map.copyOf(series), analysis.quality());
    }

    /** 在读取数据库前校验时序请求，避免非法指标或桶长度触发不必要的数据查询。 */
    public void validateSeriesRequest(List<String> requestedMetrics, int bucketMinutes) {
        resolveMetrics(requestedMetrics);
        validateBucketMinutes(bucketMinutes);
    }

    private void validateBucketMinutes(int bucketMinutes) {
        if (bucketMinutes <= 0) {
            throw new ServiceException("时间分桶分钟数必须大于0");
        }
    }

    private AnalysisData prepareAnalysis(List<RealDataEntity> rawRecords,
                                         LocalDateTime startTime, LocalDateTime endTime) {
        List<RealDataEntity> sourceRecords = rawRecords == null ? List.of() : rawRecords;
        ParseResult parseResult = parseAndFilter(sourceRecords, startTime, endTime);
        DeduplicationResult deduplication = deduplicate(parseResult.records());
        List<TimedRecord> validRecords = deduplication.records();
        DataQualitySummary quality = buildQuality(sourceRecords.size(), validRecords, deduplication.duplicateCount(),
            parseResult.invalidTimeCount(), startTime, endTime);
        return new AnalysisData(sourceRecords, validRecords, quality);
    }

    private List<String> resolveMetrics(List<String> requestedMetrics) {
        if (requestedMetrics == null || requestedMetrics.isEmpty()) {
            throw new ServiceException("遥测指标列表不能为空");
        }
        LinkedHashSet<String> metrics = new LinkedHashSet<>();
        for (String requestedMetric : requestedMetrics) {
            if (!StringUtils.hasText(requestedMetric)) {
                throw new ServiceException("遥测指标不能为空");
            }
            String metric = requestedMetric.trim();
            metricExtractor(metric);
            metrics.add(metric);
        }
        return List.copyOf(metrics);
    }

    private Set<String> resolveAggregations(List<String> requestedAggregations) {
        if (requestedAggregations == null || requestedAggregations.isEmpty()) {
            throw new ServiceException("统计方式列表不能为空");
        }
        LinkedHashSet<String> aggregations = new LinkedHashSet<>();
        for (String requestedAggregation : requestedAggregations) {
            if (!StringUtils.hasText(requestedAggregation)) {
                throw new ServiceException("统计方式不能为空");
            }
            String aggregation = requestedAggregation.trim();
            if (!Set.of("avg", "min", "max", "count").contains(aggregation)) {
                throw new ServiceException("不支持的统计方式: " + aggregation + "，仅支持 avg、min、max、count");
            }
            aggregations.add(aggregation);
        }
        return Set.copyOf(aggregations);
    }

    private Function<RealDataEntity, Float> metricExtractor(String metric) {
        return switch (metric) {
            case "dcVoltage" -> RealDataEntity::getDcVoltage;
            case "currentActual" -> RealDataEntity::getCurrentActual;
            case "speedActual" -> RealDataEntity::getSpeedActual;
            case "actualPower" -> RealDataEntity::getActualPower;
            case "motorTemp" -> RealDataEntity::getMotorTemp;
            case "inverterTemp" -> RealDataEntity::getInverterTemp;
            case "motorLoadRate" -> RealDataEntity::getMotorLoadRate;
            case "inverterLoadRate" -> RealDataEntity::getInverterLoadRate;
            default -> throw new ServiceException("不支持的遥测指标: " + metric
                + "，仅支持 dcVoltage、currentActual、speedActual、actualPower、motorTemp、inverterTemp、motorLoadRate、inverterLoadRate");
        };
    }

    /**
     * 解析 observedAt 并按业务时间做左闭右开精确过滤。
     * <p>
     * invalidTimeCount 只统计无法获得业务时间的行；缓冲区中可解析但位于精确窗口外的行仅被过滤，
     * 不视为脏数据。
     */
    private ParseResult parseAndFilter(List<RealDataEntity> rawRecords,
                                       LocalDateTime startTime, LocalDateTime endTime) {
        List<TimedRecord> records = new ArrayList<>();
        int invalidTimeCount = 0;
        for (RealDataEntity rawRecord : rawRecords) {
            LocalDateTime observedAt = observedAtParser.parse(rawRecord);
            if (observedAt == null) {
                invalidTimeCount++;
                continue;
            }
            if (observedAt.isBefore(startTime) || !observedAt.isBefore(endTime)) {
                continue;
            }
            records.add(new TimedRecord(rawRecord, observedAt));
        }
        return new ParseResult(records, invalidTimeCount);
    }

    /**
     * 按 (deviceName, inverterName, observedAt) 去重，并将结果按业务时间稳定排序。
     */
    private DeduplicationResult deduplicate(List<TimedRecord> records) {
        Map<TelemetryKey, TimedRecord> latestRecords = new LinkedHashMap<>();
        int duplicateCount = 0;
        for (TimedRecord record : records) {
            TelemetryKey key = new TelemetryKey(record.data().getDeviceName(), record.data().getInverterName(),
                record.observedAt());
            TimedRecord existing = latestRecords.get(key);
            if (existing == null) {
                latestRecords.put(key, record);
                continue;
            }
            duplicateCount++;
            if (isNewer(record, existing)) {
                latestRecords.put(key, record);
            }
        }
        List<TimedRecord> deduplicated = latestRecords.values().stream()
            .sorted(Comparator.comparing(TimedRecord::observedAt).thenComparing(record -> record.data().getId(),
                Comparator.nullsFirst(Long::compareTo)))
            .toList();
        return new DeduplicationResult(deduplicated, duplicateCount);
    }

    /**
     * 冲突记录优先保留 create_time 最新者；create_time 相同时保留 id 最大者。
     */
    private boolean isNewer(TimedRecord candidate, TimedRecord existing) {
        int createTimeCompare = compareNullable(candidate.data().getCreateTime(), existing.data().getCreateTime());
        if (createTimeCompare != 0) {
            return createTimeCompare > 0;
        }
        return compareNullable(candidate.data().getId(), existing.data().getId()) > 0;
    }

    /**
     * 提供统一的 null 排序：null 视为最旧，非 null 按自然顺序比较。
     */
    private <T extends Comparable<? super T>> int compareNullable(T left, T right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    /**
     * 基于去重后的有效采样数计算完整度，并结合阈值给出数据是否足够的确定性判断。
     */
    private DataQualitySummary buildQuality(int rawRecordCount, List<TimedRecord> validRecords, int duplicateCount,
                                            int invalidTimeCount, LocalDateTime startTime, LocalDateTime endTime) {
        long expectedCount = expectedRecordCount(startTime, endTime);
        double completeness = Math.min(1D, validRecords.size() / (double) expectedCount);
        int gapCount = calculateGapCount(validRecords, startTime, endTime);
        return new DataQualitySummary(rawRecordCount, validRecords.size(), duplicateCount, invalidTimeCount, gapCount,
            completeness, completeness >= properties.getCompletenessThreshold());
    }

    /**
     * 按窗口秒数与标称采样周期计算理论采样数；非整周期窗口向上取整。
     */
    private long expectedRecordCount(LocalDateTime startTime, LocalDateTime endTime) {
        long durationSeconds = Duration.between(startTime, endTime).toSeconds();
        return Math.max(1L, (durationSeconds + properties.getNominalSamplingSeconds() - 1)
            / properties.getNominalSamplingSeconds());
    }

    /**
     * 统计窗口首尾和相邻有效记录之间缺失的标称采样点数量。
     */
    private int calculateGapCount(List<TimedRecord> records, LocalDateTime startTime, LocalDateTime endTime) {
        LocalDateTime previous = startTime.minusSeconds(properties.getNominalSamplingSeconds());
        long missingCount = 0;
        for (TimedRecord record : records) {
            missingCount += missingSampleCount(previous, record.observedAt());
            previous = record.observedAt();
        }
        missingCount += missingSampleCount(previous, endTime);
        return missingCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) missingCount;
    }

    /**
     * 将两个观测点之间超过一个标称周期的部分换算为缺失采样点数。
     */
    private long missingSampleCount(LocalDateTime previous, LocalDateTime current) {
        long elapsedSeconds = Duration.between(previous, current).toSeconds();
        if (elapsedSeconds <= properties.getNominalSamplingSeconds()) {
            return 0;
        }
        return (elapsedSeconds - 1) / properties.getNominalSamplingSeconds();
    }

    /**
     * 按业务时间顺序提取 {@code fault_code} 与 {@code alarm_code} 两个字段中的代码，
     * 并按 G120 前缀规则统一归类。
     * <p>
     * 字段名不作为分类依据：{@code fault_code=A07089} 会被归入报警码，并记录
     * “字段与代码类型不一致”的数据质量问题；未识别格式进入 unknownCodes，不升级为故障。
     */
    private CodeExtraction extractClassifiedCodes(List<TimedRecord> records) {
        LinkedHashSet<String> faults = new LinkedHashSet<>();
        LinkedHashSet<String> alarms = new LinkedHashSet<>();
        LinkedHashSet<String> unknowns = new LinkedHashSet<>();
        LinkedHashSet<String> notes = new LinkedHashSet<>();
        for (TimedRecord record : records) {
            accumulateClassifiedCode(faults, alarms, unknowns, notes, record.data().getFaultCode(), "fault_code");
            accumulateClassifiedCode(faults, alarms, unknowns, notes, record.data().getAlarmCode(), "alarm_code");
        }
        return new CodeExtraction(List.copyOf(faults), List.copyOf(alarms), List.copyOf(unknowns),
            List.copyOf(notes));
    }

    private void accumulateClassifiedCode(LinkedHashSet<String> faults, LinkedHashSet<String> alarms,
                                          LinkedHashSet<String> unknowns, LinkedHashSet<String> notes,
                                          String rawValue, String fieldName) {
        String trimmed = normalize(rawValue);
        if (trimmed == null) {
            return;
        }
        // G120 代码规范形式为大写，遥测边界统一转换，保证展示与知识查询口径一致。
        String code = trimmed.toUpperCase(Locale.ROOT);
        switch (FaultCodeType.classify(code)) {
            case NONE -> {
                // 裸 0 或空白：采集库约定为无代码，不进入诊断编排和知识库检索。
            }
            case FAULT -> {
                if ("alarm_code".equals(fieldName)) {
                    notes.add(fieldName + " 字段出现 F 类故障码 " + code + "，字段与代码类型不一致，已按代码前缀归类为故障");
                }
                faults.add(code);
            }
            case ALARM -> {
                if ("fault_code".equals(fieldName)) {
                    notes.add(fieldName + " 字段出现 A 类报警码 " + code + "，字段与代码类型不一致，已按 G120 规则归类为报警");
                }
                alarms.add(code);
            }
            case UNKNOWN -> {
                notes.add("遥测中出现未识别代码 " + code + "，未升级为故障或报警");
                unknowns.add(code);
            }
        }
    }

    /**
     * 仅在 status、faultCode、alarmCode 三元组发生变化时生成事件，避免逐行传输遥测状态。
     * 事件中的代码同样按 G120 规则归类，与诊断编排口径一致。
     */
    private List<StatusEvent> buildStatusEvents(List<TimedRecord> records) {
        List<StatusEvent> events = new ArrayList<>();
        String previousSignature = null;
        for (TimedRecord record : records) {
            String status = normalize(record.data().getStatus());
            String[] classified = classifyRecordCodes(record.data());
            String faultCode = classified[0];
            String alarmCode = classified[1];
            if (status == null && faultCode == null && alarmCode == null) {
                continue;
            }
            String signature = String.join("|", nullToEmpty(status), nullToEmpty(faultCode), nullToEmpty(alarmCode));
            if (!signature.equals(previousSignature)) {
                events.add(new StatusEvent(record.observedAt(), status, faultCode, alarmCode));
                previousSignature = signature;
            }
        }
        return List.copyOf(events);
    }

    /**
     * 将一条记录的 fault_code 与 alarm_code 字段归类为 [故障码, 报警码] 二元组，
     * 两个字段均参与两种类型的归集，未识别格式不出现在事件中。
     */
    private String[] classifyRecordCodes(RealDataEntity data) {
        String faultCode = null;
        String alarmCode = null;
        for (String rawValue : new String[]{data.getFaultCode(), data.getAlarmCode()}) {
            String trimmed = normalize(rawValue);
            if (trimmed == null) {
                continue;
            }
            String code = trimmed.toUpperCase(Locale.ROOT);
            switch (FaultCodeType.classify(code)) {
                case FAULT -> faultCode = code;
                case ALARM -> alarmCode = code;
                default -> {
                }
            }
        }
        return new String[]{faultCode, alarmCode};
    }

    /**
     * 汇总功率、温度和负载率。空数值序列返回 null，而不是具有误导性的 0。
     */
    private TelemetryStatistics buildStatistics(List<TimedRecord> records) {
        NumericSummary actualPower = summarize(records, RealDataEntity::getActualPower);
        NumericSummary motorTemp = summarize(records, RealDataEntity::getMotorTemp);
        NumericSummary inverterTemp = summarize(records, RealDataEntity::getInverterTemp);
        NumericSummary inverterLoadRate = summarize(records, RealDataEntity::getInverterLoadRate);
        NumericSummary motorLoadRate = summarize(records, RealDataEntity::getMotorLoadRate);
        return new TelemetryStatistics(records.size(), actualPower.min(), actualPower.max(), actualPower.average(),
            motorTemp.min(), motorTemp.max(), motorTemp.average(), inverterTemp.min(), inverterTemp.max(),
            inverterTemp.average(), inverterLoadRate.max(), motorLoadRate.max());
    }

    /**
     * 对一个可空 Float 指标计算最小值、最大值和平均值。
     */
    private NumericSummary summarize(List<TimedRecord> records, Function<RealDataEntity, Float> extractor) {
        DoubleSummaryStatistics statistics = records.stream().map(TimedRecord::data).map(extractor)
            .filter(Objects::nonNull).mapToDouble(Float::doubleValue).summaryStatistics();
        if (statistics.getCount() == 0) {
            return new NumericSummary(null, null, null, 0L);
        }
        return new NumericSummary(round(statistics.getMin()), round(statistics.getMax()), round(statistics.getAverage()),
            statistics.getCount());
    }

    /**
     * 计算运行报告级统计量：电机温度与电机负载率的峰值出现时间，以及故障/报警码的出现情况。
     * <p>
     * 代码归类口径与 {@link #buildStatusEvents} 保持一致；峰值时间跟随严格最大值，
     * 窗口内没有对应有效值时返回 null。
     */
    private OperationStatistics buildOperationStatistics(List<TimedRecord> records) {
        LocalDateTime maxMotorTempAt = peakObservedAt(records, RealDataEntity::getMotorTemp);
        LocalDateTime maxMotorLoadRateAt = peakObservedAt(records, RealDataEntity::getMotorLoadRate);
        Map<String, OccurrenceAccumulator> faults = new LinkedHashMap<>();
        Map<String, OccurrenceAccumulator> alarms = new LinkedHashMap<>();
        for (TimedRecord record : records) {
            String[] classified = classifyRecordCodes(record.data());
            if (classified[0] != null) {
                accumulateOccurrence(faults, classified[0], record.observedAt());
            }
            if (classified[1] != null) {
                accumulateOccurrence(alarms, classified[1], record.observedAt());
            }
        }
        return new OperationStatistics(maxMotorTempAt, maxMotorLoadRateAt,
            toOccurrences(faults, records, true), toOccurrences(alarms, records, false));
    }

    /**
     * 返回某个可空 Float 指标严格最大值对应的业务时间；无有效值时为 null。
     */
    private LocalDateTime peakObservedAt(List<TimedRecord> records, Function<RealDataEntity, Float> extractor) {
        LocalDateTime peakAt = null;
        Float peakValue = null;
        for (TimedRecord record : records) {
            Float value = extractor.apply(record.data());
            if (value == null) {
                continue;
            }
            if (peakValue == null || value > peakValue) {
                peakValue = value;
                peakAt = record.observedAt();
            }
        }
        return peakAt;
    }

    private void accumulateOccurrence(Map<String, OccurrenceAccumulator> target, String code,
                                      LocalDateTime observedAt) {
        target.computeIfAbsent(code, key -> new OccurrenceAccumulator(observedAt)).accept(observedAt);
    }

    private List<CodeOccurrence> toOccurrences(Map<String, OccurrenceAccumulator> accumulators,
                                                List<TimedRecord> records, boolean fault) {
        List<CodeOccurrence> occurrences = new ArrayList<>();
        for (Map.Entry<String, OccurrenceAccumulator> entry : accumulators.entrySet()) {
            OccurrenceAccumulator accumulator = entry.getValue();
            CodeState state = codeStateAtWindowEnd(entry.getKey(), accumulator.lastObservedAt, records, fault);
            occurrences.add(new CodeOccurrence(entry.getKey(), accumulator.count,
                accumulator.firstObservedAt, accumulator.lastObservedAt, state.active(), state.recoveredAt()));
        }
        return List.copyOf(occurrences);
    }

    /** 在已解析的窗口内确认代码是否持续至末尾，并给出最后一次消失的确认时间。 */
    private CodeState codeStateAtWindowEnd(String code, LocalDateTime lastObservedAt,
                                            List<TimedRecord> records, boolean fault) {
        LocalDateTime recoveredAt = null;
        String latestCode = records.isEmpty() ? null : classifyRecordCodes(records.get(records.size() - 1).data())[fault ? 0 : 1];
        for (TimedRecord record : records) {
            String observedCode = classifyRecordCodes(record.data())[fault ? 0 : 1];
            if (record.observedAt().isAfter(lastObservedAt) && !code.equals(observedCode) && recoveredAt == null) {
                recoveredAt = record.observedAt();
            }
        }
        boolean active = code.equals(latestCode);
        return new CodeState(active, active ? null : recoveredAt);
    }

    private Double round(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /** 按业务时间顺序累计单个代码的出现次数与首末时间。 */
    private static final class OccurrenceAccumulator {
        private int count;
        private final LocalDateTime firstObservedAt;
        private LocalDateTime lastObservedAt;

        private OccurrenceAccumulator(LocalDateTime firstObservedAt) {
            this.firstObservedAt = firstObservedAt;
        }

        private void accept(LocalDateTime observedAt) {
            count++;
            lastObservedAt = observedAt;
        }
    }

    /**
     * 为本次查询生成证据来源摘要。
     * <p>
     * 摘要覆盖查询条件、质量配置以及数据库粗筛返回的每一行已映射字段。这样即使两个窗口得到相同
     * 统计值，只要底层证据不同，sourceDigest 也会不同；原始值只参与后端哈希，不会进入返回对象。
     */
    private String sourceDigest(String deviceName, String inverterName,
                                LocalDateTime startTime, LocalDateTime endTime,
                                List<RealDataEntity> rawRecords, DataQualitySummary quality) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, deviceName, inverterName, startTime, endTime, properties.getTimezone(),
                properties.getNominalSamplingSeconds(), properties.getCompletenessThreshold(),
                quality.rawRecordCount(), quality.validRecordCount(), quality.duplicateCount(),
                quality.invalidTimeCount(), quality.gapCount());
            for (RealDataEntity data : rawRecords) {
                updateDigest(digest, data.getId(), data.getCreateTime(), data.getDeviceName(),
                    data.getInverterName(), data.getTimestamp(), data.getDate(), data.getTime(), data.getStatus(),
                    data.getFaultCode(), data.getAlarmCode(), data.getDcVoltage(), data.getSpeedSetpoint(),
                    data.getSpeedActual(), data.getCurrentActual(), data.getTorqueSetpoint(), data.getTorqueActual(),
                    data.getAirIntakeTemp(), data.getMotorTemp(), data.getInverterTemp(), data.getActualPower(),
                    data.getInverterRadiatorTemp(), data.getInverterLoadRate(), data.getMotorLoadRate());
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 使用长度前缀写入摘要，避免不同字段组合在简单字符串拼接时产生边界碰撞。
     */
    private void updateDigest(MessageDigest digest, Object... values) {
        for (Object value : values) {
            byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }

    /**
     * 将空白字符串归一化为 null，确保事件和编码提取口径一致。
     */
    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 构造事件状态签名时，将 null 转为空字符串以保持字段位置稳定。
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 解析成功且处于精确窗口内的记录。 */
    private record TimedRecord(RealDataEntity data, LocalDateTime observedAt) {
    }

    /** 去重使用的业务主键。 */
    private record TelemetryKey(String deviceName, String inverterName, LocalDateTime observedAt) {
    }

    /** 时间解析和精确过滤结果。 */
    private record ParseResult(List<TimedRecord> records, int invalidTimeCount) {
    }

    /** 去重结果及被折叠的重复行数。 */
    private record DeduplicationResult(List<TimedRecord> records, int duplicateCount) {
    }

    /** 同一份受控查询数据经时间过滤、去重和质量计算后的中间结果。 */
    private record AnalysisData(List<RealDataEntity> rawRecords, List<TimedRecord> validRecords,
                                DataQualitySummary quality) {
    }

    /** 单个数值指标的统计摘要。 */
    private record NumericSummary(Double min, Double max, Double average, long count) {
    }

    /** 单个时间桶内的可空数值指标累加器。 */
    private static final class SeriesBucketAccumulator {
        private double total;
        private long count;

        private void accept(Float value) {
            total += value;
            count++;
        }

        private double average() {
            return total / count;
        }

        private long count() {
            return count;
        }
    }

    /** 单个代码在窗口末尾的活动性与恢复确认时间。 */
    private record CodeState(boolean active, LocalDateTime recoveredAt) {
    }

    /** 按 G120 规则归类后的代码提取结果。 */
    private record CodeExtraction(List<String> faultCodes, List<String> alarmCodes, List<String> unknownCodes,
                                  List<String> notes) {
    }

}
