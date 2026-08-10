package org.ruoyi.fault.telemetry.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.telemetry.entity.RealDataEntity;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证遥测分析的核心确定性口径：时间回退、精确过滤、冲突去重、质量和证据摘要。
 */
@Tag("dev")
class TelemetryDataAnalyzerTest {

    private static final LocalDateTime START_TIME = LocalDateTime.of(2026, 7, 24, 9, 0);
    private static final LocalDateTime END_TIME = START_TIME.plusSeconds(2);

    private TelemetryDataAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        FaultDiagnosisProperties properties = new FaultDiagnosisProperties();
        properties.setNominalSamplingSeconds(1);
        properties.setCompletenessThreshold(0.8D);
        TelemetryObservedAtParser parser = new TelemetryObservedAtParser(properties);
        analyzer = new TelemetryDataAnalyzer(parser, properties);
    }

    @Test
    void analyzesWithTimestampFallbackAndKeepsNewestDuplicate() {
        RealDataEntity olderDuplicate = telemetry(1L, "2026-07-24 09:00:00", null, null,
            START_TIME.plusSeconds(5), "FAULT", "F_OLD", 10F);
        RealDataEntity newerDuplicate = telemetry(2L, "2026-07-24 09:00:00", null, null,
            START_TIME.plusSeconds(6), "FAULT", "F30005", 20F);
        RealDataEntity dateTimeFallback = telemetry(3L, "invalid", "2026-07-24", "09:00:01",
            START_TIME.plusSeconds(7), "RUNNING", null, 30F);
        RealDataEntity invalidTime = telemetry(4L, "invalid", "invalid", "invalid",
            START_TIME.plusSeconds(8), "RUNNING", null, 40F);

        TelemetryQueryResult result = analyzer.analyze("G120-1", "INV-1", START_TIME, END_TIME,
            List.of(olderDuplicate, newerDuplicate, dateTimeFallback, invalidTime));

        assertEquals(4, result.quality().rawRecordCount());
        assertEquals(2, result.quality().validRecordCount());
        assertEquals(1, result.quality().duplicateCount());
        assertEquals(1, result.quality().invalidTimeCount());
        assertEquals(0, result.quality().gapCount());
        assertEquals(1D, result.quality().completeness());
        assertTrue(result.quality().sufficient());
        assertEquals(List.of("F30005"), result.faultCodes());
        assertEquals(2, result.statusEvents().size());
        assertEquals(20D, result.statistics().minActualPower());
        assertEquals(30D, result.statistics().maxActualPower());
        assertEquals(25D, result.statistics().avgActualPower());
        assertTrue(result.sourceDigest().startsWith("sha256:"));
    }

    @Test
    void sourceDigestChangesWhenUnderlyingEvidenceChanges() {
        RealDataEntity record = telemetry(1L, "2026-07-24 09:00:00", null, null,
            START_TIME.plusSeconds(1), "RUNNING", null, 10F);
        TelemetryQueryResult before = analyzer.analyze("G120-1", "INV-1", START_TIME, END_TIME, List.of(record));

        record.setActualPower(11F);
        TelemetryQueryResult after = analyzer.analyze("G120-1", "INV-1", START_TIME, END_TIME, List.of(record));

        assertNotEquals(before.sourceDigest(), after.sourceDigest());
    }

    @Test
    void treatsBareZeroFaultCodeAsNoFault() {
        RealDataEntity record = telemetry(1L, "2026-07-24 09:00:00", null, null,
            START_TIME.plusSeconds(1), "RUNNING", " 0 ", 10F);

        TelemetryQueryResult result = analyzer.analyze("G120-1", "INV-1", START_TIME, END_TIME, List.of(record));

        assertEquals(List.of(), result.faultCodes());
        assertEquals(1, result.statusEvents().size());
        assertEquals(null, result.statusEvents().get(0).faultCode());
    }

    @Test
    void classifiesAlarmCodeWrittenIntoFaultCodeFieldAsAlarm() {
        RealDataEntity record = telemetry(1L, "2026-07-24 09:00:00", null, null,
            START_TIME.plusSeconds(1), "RUNNING", "A07089", 10F);

        TelemetryQueryResult result = analyzer.analyze("G120-1", "INV-1", START_TIME, END_TIME, List.of(record));

        assertEquals(List.of(), result.faultCodes());
        assertEquals(List.of("A07089"), result.alarmCodes());
        assertTrue(result.codeNormalizationNotes().stream().anyMatch(note -> note.contains("字段与代码类型不一致")));
        assertEquals(1, result.statusEvents().size());
        assertEquals(null, result.statusEvents().get(0).faultCode());
        assertEquals("A07089", result.statusEvents().get(0).alarmCode());
    }

    @Test
    void classifiesCodesByPrefixAcrossBothFields() {
        RealDataEntity fault = telemetry(1L, "2026-07-24 09:00:00", null, null,
            START_TIME.plusSeconds(1), "FAULT", "F30899", 10F);
        RealDataEntity alarm = telemetryWithAlarm(2L, "2026-07-24 09:00:01", START_TIME.plusSeconds(2),
            "RUNNING", "0", "a07089");

        TelemetryQueryResult result = analyzer.analyze("G120-1", "INV-1", START_TIME,
            START_TIME.plusSeconds(3), List.of(fault, alarm));

        assertEquals(List.of("F30899"), result.faultCodes());
        assertEquals(List.of("A07089"), result.alarmCodes());
        assertTrue(result.codeNormalizationNotes().isEmpty());
    }

    @Test
    void keepsUnknownCodesOutOfFaultAndAlarmLists() {
        RealDataEntity record = telemetry(1L, "2026-07-24 09:00:00", null, null,
            START_TIME.plusSeconds(1), "RUNNING", "XYZ-1", 10F);

        TelemetryQueryResult result = analyzer.analyze("G120-1", "INV-1", START_TIME, END_TIME, List.of(record));

        assertEquals(List.of(), result.faultCodes());
        assertEquals(List.of(), result.alarmCodes());
        assertEquals(List.of("XYZ-1"), result.unknownCodes());
        assertTrue(result.codeNormalizationNotes().stream().anyMatch(note -> note.contains("未识别代码")));
        assertEquals(null, result.statusEvents().get(0).faultCode());
    }

    @Test
    void reportsLatestObservedAtFromLastValidRecord() {
        RealDataEntity first = telemetry(1L, "2026-07-24 09:00:00", null, null,
            START_TIME.plusSeconds(5), "RUNNING", "0", 10F);
        RealDataEntity last = telemetry(2L, "2026-07-24 09:00:01", null, null,
            START_TIME.plusSeconds(6), "RUNNING", "0", 11F);

        TelemetryQueryResult result = analyzer.analyze("G120-1", "INV-1", START_TIME, START_TIME.plusSeconds(2),
            List.of(last, first));

        assertEquals(START_TIME.plusSeconds(1), result.latestObservedAt());
    }

    @Test
    void latestObservedAtIsNullWithoutValidRecords() {
        TelemetryQueryResult result = analyzer.analyze("G120-1", "INV-1", START_TIME, END_TIME, List.of());

        assertEquals(null, result.latestObservedAt());
    }

    @Test
    void distinguishesRecoveredAlarmFromCurrentStateAndRoundsStatistics() {
        RealDataEntity alarm = telemetryWithAlarm(1L, "2026-07-24 09:00:00", START_TIME,
            "0", "0", "A07089");
        alarm.setMotorTemp(39.997001F);
        RealDataEntity recovered = telemetryWithAlarm(2L, "2026-07-24 09:00:01", START_TIME.plusSeconds(1),
            "0", "0", "0");
        recovered.setMotorTemp(40.123999F);

        TelemetryQueryResult result = analyzer.analyze("G120-1", "INV-1", START_TIME, END_TIME,
            List.of(alarm, recovered));

        assertEquals("NORMAL", result.currentState().status());
        assertEquals("0", result.currentState().statusCode());
        assertEquals(List.of(), result.currentState().activeAlarmCodes());
        assertEquals(List.of("A07089"), result.windowFindings().alarmCodes());
        assertEquals(false, result.operation().alarmCodeOccurrences().get(0).active());
        assertEquals(START_TIME.plusSeconds(1), result.operation().alarmCodeOccurrences().get(0).recoveredAt());
        assertEquals(39.997D, result.statistics().minMotorTemp());
        assertEquals(40.124D, result.statistics().maxMotorTemp());
    }

    /**
     * 构造一条仅包含本测试所需字段的 real_data 记录。
     */
    @Test
    void operationStatisticsTrackCodeOccurrencesAndPeakTimes() {
        LocalDateTime start = LocalDateTime.of(2026, 7, 24, 9, 0);
        LocalDateTime end = start.plusSeconds(3);
        RealDataEntity first = telemetryWithAlarm(1L, "2026-07-24 09:00:00", start,
            "FAULT", "F30005", "0");
        first.setMotorTemp(50F);
        first.setMotorLoadRate(80F);
        RealDataEntity second = telemetryWithAlarm(2L, "2026-07-24 09:00:01", start.plusSeconds(1),
            "FAULT", "F30005", "A07089");
        second.setMotorTemp(76F);
        second.setMotorLoadRate(104.3F);
        RealDataEntity third = telemetryWithAlarm(3L, "2026-07-24 09:00:02", start.plusSeconds(2),
            "FAULT", "0", "A07089");
        third.setMotorTemp(60F);
        third.setMotorLoadRate(90F);

        TelemetryQueryResult result = analyzer.analyze("G120-1", "INV-1", start, end,
            List.of(first, second, third));

        assertEquals(1, result.operation().faultCodeOccurrences().size());
        assertEquals("F30005", result.operation().faultCodeOccurrences().get(0).code());
        assertEquals(2, result.operation().faultCodeOccurrences().get(0).sampleCount());
        assertEquals(start, result.operation().faultCodeOccurrences().get(0).firstObservedAt());
        assertEquals(start.plusSeconds(1), result.operation().faultCodeOccurrences().get(0).lastObservedAt());
        assertEquals(1, result.operation().alarmCodeOccurrences().size());
        assertEquals("A07089", result.operation().alarmCodeOccurrences().get(0).code());
        assertEquals(2, result.operation().alarmCodeOccurrences().get(0).sampleCount());
        assertEquals(start.plusSeconds(1), result.operation().maxMotorTempAt());
        assertEquals(start.plusSeconds(1), result.operation().maxMotorLoadRateAt());
    }

    private RealDataEntity telemetry(Long id, String timestamp, String date, String time,
                                     LocalDateTime createTime, String status, String faultCode, Float actualPower) {
        RealDataEntity entity = new RealDataEntity();
        entity.setId(id);
        entity.setTimestamp(timestamp);
        entity.setDate(date);
        entity.setTime(time);
        entity.setCreateTime(createTime);
        entity.setDeviceName("G120-1");
        entity.setInverterName("INV-1");
        entity.setStatus(status);
        entity.setFaultCode(faultCode);
        entity.setActualPower(actualPower);
        return entity;
    }

    private RealDataEntity telemetryWithAlarm(Long id, String timestamp, LocalDateTime createTime,
                                              String status, String faultCode, String alarmCode) {
        RealDataEntity entity = telemetry(id, timestamp, null, null, createTime, status, faultCode, 10F);
        entity.setAlarmCode(alarmCode);
        return entity;
    }

}
