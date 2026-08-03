package org.ruoyi.fault.telemetry.analysis;

import lombok.RequiredArgsConstructor;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.telemetry.entity.RealDataEntity;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.StatusEvent;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetryStatistics;
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
import java.util.Map;
import java.util.Objects;
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
        ParseResult parseResult = parseAndFilter(rawRecords, startTime, endTime);
        DeduplicationResult deduplication = deduplicate(parseResult.records());
        List<TimedRecord> validRecords = deduplication.records();

        DataQualitySummary quality = buildQuality(rawRecords.size(), validRecords, deduplication.duplicateCount(),
            parseResult.invalidTimeCount(), startTime, endTime);
        List<String> faultCodes = distinctCodes(validRecords, RealDataEntity::getFaultCode);
        List<String> alarmCodes = distinctCodes(validRecords, RealDataEntity::getAlarmCode);
        List<StatusEvent> statusEvents = buildStatusEvents(validRecords);
        TelemetryStatistics statistics = buildStatistics(validRecords);
        String sourceDigest = sourceDigest(deviceName, inverterName, startTime, endTime, rawRecords, quality);

        return new TelemetryQueryResult(deviceName, startTime, endTime, quality, faultCodes, alarmCodes,
            statusEvents, statistics, sourceDigest, false);
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
     * 按业务时间顺序提取非空且不重复的故障码或报警码。
     */
    private List<String> distinctCodes(List<TimedRecord> records, Function<RealDataEntity, String> extractor) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (TimedRecord record : records) {
            String code = normalize(extractor.apply(record.data()));
            if (code != null) {
                codes.add(code);
            }
        }
        return List.copyOf(codes);
    }

    /**
     * 仅在 status、faultCode、alarmCode 三元组发生变化时生成事件，避免逐行传输遥测状态。
     */
    private List<StatusEvent> buildStatusEvents(List<TimedRecord> records) {
        List<StatusEvent> events = new ArrayList<>();
        String previousSignature = null;
        for (TimedRecord record : records) {
            String status = normalize(record.data().getStatus());
            String faultCode = normalize(record.data().getFaultCode());
            String alarmCode = normalize(record.data().getAlarmCode());
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
            return new NumericSummary(null, null, null);
        }
        return new NumericSummary(statistics.getMin(), statistics.getMax(), statistics.getAverage());
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

    /** 单个数值指标的统计摘要。 */
    private record NumericSummary(Double min, Double max, Double average) {
    }

}
