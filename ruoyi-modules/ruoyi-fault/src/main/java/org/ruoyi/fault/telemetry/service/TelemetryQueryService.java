package org.ruoyi.fault.telemetry.service;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.telemetry.entity.RealDataEntity;
import org.ruoyi.fault.telemetry.mapper.RealDataMapper;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.StatusEvent;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetryStatistics;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 受控运行数据查询服务。
 * <p>
 * 此服务只接受设备、逆变器与时间窗参数，绝不执行由 Agent 提供的 SQL。
 */
@Service
@RequiredArgsConstructor
public class TelemetryQueryService {

    private static final List<DateTimeFormatter> LOCAL_DATE_TIME_FORMATTERS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd")
    );

    private static final List<DateTimeFormatter> TIME_FORMATTERS = List.of(
        DateTimeFormatter.ISO_LOCAL_TIME,
        DateTimeFormatter.ofPattern("HH:mm:ss"),
        DateTimeFormatter.ofPattern("HH:mm")
    );

    private final RealDataMapper realDataMapper;
    private final FaultDiagnosisProperties properties;

    /**
     * 查询一个受授权资产在指定窗口内的遥测诊断摘要。
     */
    public TelemetryQueryResult queryTelemetry(String deviceName, String inverterName,
                                                LocalDateTime startTime, LocalDateTime endTime) {
        validateRequest(deviceName, inverterName, startTime, endTime);

        int bufferSeconds = properties.getCreateTimeBufferSeconds();
        LocalDateTime queryStart = startTime.minusSeconds(bufferSeconds);
        LocalDateTime queryEnd = endTime.plusSeconds(bufferSeconds);
        List<RealDataEntity> rawRecords = realDataMapper.selectTelemetry(deviceName, inverterName, queryStart, queryEnd);
        if (rawRecords == null) {
            rawRecords = List.of();
        }

        ParseResult parseResult = parseAndFilter(rawRecords, startTime, endTime);
        DeduplicationResult deduplication = deduplicate(parseResult.records());
        List<TimedRecord> validRecords = deduplication.records();
        DataQualitySummary quality = buildQuality(rawRecords.size(), validRecords, deduplication.duplicateCount(),
            parseResult.invalidTimeCount(), startTime, endTime);
        List<String> faultCodes = distinctCodes(validRecords, RealDataEntity::getFaultCode);
        List<String> alarmCodes = distinctCodes(validRecords, RealDataEntity::getAlarmCode);
        List<StatusEvent> statusEvents = buildStatusEvents(validRecords);
        TelemetryStatistics statistics = buildStatistics(validRecords);

        return new TelemetryQueryResult(deviceName, startTime, endTime, quality, faultCodes, alarmCodes,
            statusEvents, statistics, sourceDigest(deviceName, inverterName, startTime, endTime, quality,
            faultCodes, alarmCodes));
    }

    private void validateRequest(String deviceName, String inverterName, LocalDateTime startTime, LocalDateTime endTime) {
        if (!properties.isEnabled()) {
            throw new ServiceException("故障诊断功能未启用");
        }
        if (!StringUtils.hasText(deviceName) || !StringUtils.hasText(inverterName)) {
            throw new ServiceException("设备名称和逆变器名称不能为空");
        }
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new ServiceException("诊断时间范围无效");
        }
        if (properties.getMaxWindowMinutes() <= 0 || properties.getNominalSamplingSeconds() <= 0
            || properties.getCreateTimeBufferSeconds() < 0) {
            throw new ServiceException("故障诊断查询配置无效");
        }
        if (Duration.between(startTime, endTime).compareTo(Duration.ofMinutes(properties.getMaxWindowMinutes())) > 0) {
            throw new ServiceException("诊断时间范围超过最大允许窗口");
        }
        if (properties.getAllowedAssets() == null || properties.getAllowedAssets().stream()
            .filter(Objects::nonNull).noneMatch(deviceName::equals)) {
            throw new ServiceException("当前无设备诊断权限: " + deviceName);
        }
        try {
            ZoneId.of(properties.getTimezone());
        } catch (Exception e) {
            throw new ServiceException("故障诊断时区配置无效: " + properties.getTimezone());
        }
    }

    private ParseResult parseAndFilter(List<RealDataEntity> rawRecords, LocalDateTime startTime, LocalDateTime endTime) {
        List<TimedRecord> records = new ArrayList<>();
        int invalidTimeCount = 0;
        for (RealDataEntity rawRecord : rawRecords) {
            LocalDateTime observedAt = parseObservedAt(rawRecord);
            if (observedAt == null) {
                invalidTimeCount++;
                continue;
            }
            // 精确过滤采用左闭右开区间，与数据库粗筛的语义一致。
            if (observedAt.isBefore(startTime) || !observedAt.isBefore(endTime)) {
                continue;
            }
            records.add(new TimedRecord(rawRecord, observedAt));
        }
        return new ParseResult(records, invalidTimeCount);
    }

    /**
     * observedAt 优先取 timestamp；无法解析时回退到 date + time。
     */
    private LocalDateTime parseObservedAt(RealDataEntity record) {
        LocalDateTime timestamp = parseDateTime(record.getTimestamp());
        if (timestamp != null) {
            return timestamp;
        }
        if (!StringUtils.hasText(record.getDate()) || !StringUtils.hasText(record.getTime())) {
            return null;
        }
        for (DateTimeFormatter dateFormatter : DATE_FORMATTERS) {
            for (DateTimeFormatter timeFormatter : TIME_FORMATTERS) {
                try {
                    return LocalDateTime.of(LocalDate.parse(record.getDate().trim(), dateFormatter),
                        LocalTime.parse(record.getTime().trim(), timeFormatter));
                } catch (DateTimeParseException ignored) {
                    // 尝试下一个格式。
                }
            }
        }
        return null;
    }

    private LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        for (DateTimeFormatter formatter : LOCAL_DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一个格式。
            }
        }
        try {
            return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .atZoneSameInstant(ZoneId.of(properties.getTimezone())).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private DeduplicationResult deduplicate(List<TimedRecord> records) {
        Map<TelemetryKey, TimedRecord> latestRecords = new LinkedHashMap<>();
        int duplicateCount = 0;
        for (TimedRecord record : records) {
            TelemetryKey key = new TelemetryKey(record.data().getDeviceName(), record.data().getInverterName(), record.observedAt());
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
     * 冲突时优先最新 create_time；create_time 相同则保留 id 最大的记录。
     */
    private boolean isNewer(TimedRecord candidate, TimedRecord existing) {
        int createTimeCompare = compareNullable(candidate.data().getCreateTime(), existing.data().getCreateTime());
        if (createTimeCompare != 0) {
            return createTimeCompare > 0;
        }
        return compareNullable(candidate.data().getId(), existing.data().getId()) > 0;
    }

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

    private DataQualitySummary buildQuality(int rawRecordCount, List<TimedRecord> validRecords, int duplicateCount,
                                            int invalidTimeCount, LocalDateTime startTime, LocalDateTime endTime) {
        long expectedCount = expectedRecordCount(startTime, endTime);
        double completeness = Math.min(1D, validRecords.size() / (double) expectedCount);
        int gapCount = calculateGapCount(validRecords, startTime, endTime);
        return new DataQualitySummary(rawRecordCount, validRecords.size(), duplicateCount, invalidTimeCount, gapCount,
            completeness, completeness >= properties.getCompletenessThreshold());
    }

    private long expectedRecordCount(LocalDateTime startTime, LocalDateTime endTime) {
        long durationSeconds = Duration.between(startTime, endTime).toSeconds();
        return Math.max(1L, (durationSeconds + properties.getNominalSamplingSeconds() - 1)
            / properties.getNominalSamplingSeconds());
    }

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

    private long missingSampleCount(LocalDateTime previous, LocalDateTime current) {
        long elapsedSeconds = Duration.between(previous, current).toSeconds();
        if (elapsedSeconds <= properties.getNominalSamplingSeconds()) {
            return 0;
        }
        return (elapsedSeconds - 1) / properties.getNominalSamplingSeconds();
    }

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

    private NumericSummary summarize(List<TimedRecord> records, Function<RealDataEntity, Float> extractor) {
        DoubleSummaryStatistics statistics = records.stream().map(TimedRecord::data).map(extractor)
            .filter(Objects::nonNull).mapToDouble(Float::doubleValue).summaryStatistics();
        if (statistics.getCount() == 0) {
            return new NumericSummary(null, null, null);
        }
        return new NumericSummary(statistics.getMin(), statistics.getMax(), statistics.getAverage());
    }

    private String sourceDigest(String deviceName, String inverterName, LocalDateTime startTime, LocalDateTime endTime,
                                DataQualitySummary quality, List<String> faultCodes, List<String> alarmCodes) {
        String source = String.join("|", deviceName, inverterName, startTime.toString(), endTime.toString(),
            properties.getTimezone(), String.valueOf(quality.rawRecordCount()), String.valueOf(quality.validRecordCount()),
            String.valueOf(quality.duplicateCount()), String.valueOf(quality.invalidTimeCount()),
            String.join(",", faultCodes), String.join(",", alarmCodes));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record TimedRecord(RealDataEntity data, LocalDateTime observedAt) {
    }

    private record TelemetryKey(String deviceName, String inverterName, LocalDateTime observedAt) {
    }

    private record ParseResult(List<TimedRecord> records, int invalidTimeCount) {
    }

    private record DeduplicationResult(List<TimedRecord> records, int duplicateCount) {
    }

    private record NumericSummary(Double min, Double max, Double average) {
    }

}
