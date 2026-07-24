package org.ruoyi.fault.telemetry.analysis;

import lombok.RequiredArgsConstructor;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.telemetry.entity.RealDataEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 将 {@link RealDataEntity} 中的业务观测时间解析为统一的 {@link LocalDateTime}。
 * <p>
 * 解析顺序是确定的：先尝试 timestamp，再尝试 date + time。带偏移量的 timestamp 会按照
 * fault.diagnosis.timezone 转换，整个过程不会读取 JVM 默认时区。
 */
@Component
@RequiredArgsConstructor
public class TelemetryObservedAtParser {

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

    private final FaultDiagnosisProperties properties;

    /**
     * 解析一条遥测记录的真实观测时间。
     *
     * @param record 数据库遥测记录
     * @return 成功时返回观测时间，所有受支持格式均失败时返回 null
     */
    public LocalDateTime parse(RealDataEntity record) {
        LocalDateTime timestamp = parseTimestamp(record.getTimestamp());
        if (timestamp != null) {
            return timestamp;
        }
        return parseDateAndTime(record.getDate(), record.getTime());
    }

    /**
     * 解析 timestamp。无时区字符串直接作为配置时区下的本地时间；带偏移量字符串先做时区换算。
     */
    private LocalDateTime parseTimestamp(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        for (DateTimeFormatter formatter : LOCAL_DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // 当前格式不匹配时按声明顺序继续尝试，最终失败统一返回 null。
            }
        }
        try {
            return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .atZoneSameInstant(ZoneId.of(properties.getTimezone())).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * timestamp 不可用时，将独立的 date、time 字段组合为观测时间。
     */
    private LocalDateTime parseDateAndTime(String dateValue, String timeValue) {
        if (!StringUtils.hasText(dateValue) || !StringUtils.hasText(timeValue)) {
            return null;
        }
        for (DateTimeFormatter dateFormatter : DATE_FORMATTERS) {
            for (DateTimeFormatter timeFormatter : TIME_FORMATTERS) {
                try {
                    LocalDate date = LocalDate.parse(dateValue.trim(), dateFormatter);
                    LocalTime time = LocalTime.parse(timeValue.trim(), timeFormatter);
                    return LocalDateTime.of(date, time);
                } catch (DateTimeParseException ignored) {
                    // 组合格式不匹配时继续尝试下一组。
                }
            }
        }
        return null;
    }

}
