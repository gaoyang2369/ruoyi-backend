package org.ruoyi.fault.telemetry.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.telemetry.analysis.TelemetryDataAnalyzer;
import org.ruoyi.fault.telemetry.analysis.TelemetryObservedAtParser;
import org.ruoyi.fault.telemetry.entity.RealDataEntity;
import org.ruoyi.fault.telemetry.mapper.RealDataMapper;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetryReportSnapshot;
import org.ruoyi.fault.telemetry.model.TelemetryStatisticsResult;
import org.ruoyi.fault.telemetry.model.TelemetrySeriesResult;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("dev")
class TelemetryQueryServiceTest {

    private static final String DEVICE_ONE = "G120电机1";
    private static final String DEVICE_TWO = "G120电机2";
    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 7, 19, 14, 50, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 7, 19, 15, 0, 0);

    @Mock
    private RealDataMapper realDataMapper;

    private FaultDiagnosisProperties properties;
    private TelemetryQueryService service;

    @BeforeEach
    void setUp() {
        properties = new FaultDiagnosisProperties();
        properties.setAllowedAssets(List.of(DEVICE_ONE, DEVICE_TWO));
        properties.setTelemetryTable("real_data");
        Map<String, String> deviceTables = new LinkedHashMap<>();
        deviceTables.put(DEVICE_ONE, "real_data_01");
        deviceTables.put(DEVICE_TWO, "real_data_02");
        properties.setDeviceTelemetryTables(deviceTables);
        service = new TelemetryQueryService(realDataMapper,
            new TelemetryDataAnalyzer(new TelemetryObservedAtParser(properties), properties), properties);
    }

    @Test
    void routesDeviceToItsConfiguredTableAndAppliesBuffer() {
        when(realDataMapper.selectTelemetry(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(List.of(entity("2026-07-19 14:50:01")));

        TelemetryQueryResult result = service.queryTelemetry(DEVICE_TWO, DEVICE_TWO, WINDOW_START, WINDOW_END);

        ArgumentCaptor<String> table = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(realDataMapper).selectTelemetry(table.capture(), eq(DEVICE_TWO), eq(DEVICE_TWO),
            start.capture(), end.capture());
        assertEquals("real_data_02", table.getValue());
        assertEquals(WINDOW_START.minusSeconds(properties.getCreateTimeBufferSeconds()), start.getValue());
        assertEquals(WINDOW_END.plusSeconds(properties.getCreateTimeBufferSeconds()), end.getValue());
        assertEquals(1, result.quality().validRecordCount());
        assertFalse(result.fallbackToLatestData());
    }

    @Test
    void usesDefaultTableForUnmappedDevice() {
        when(realDataMapper.selectTelemetry(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(List.of(entity("2026-07-19 14:50:01")));

        service.queryTelemetry(DEVICE_ONE, DEVICE_ONE, WINDOW_START, WINDOW_END);

        // 将设备一的路由清空后应回退到默认表
        properties.setDeviceTelemetryTables(new LinkedHashMap<>());
        service.queryTelemetry(DEVICE_ONE, DEVICE_ONE, WINDOW_START, WINDOW_END);

        ArgumentCaptor<String> tables = ArgumentCaptor.forClass(String.class);
        verify(realDataMapper, times(2)).selectTelemetry(tables.capture(), eq(DEVICE_ONE), eq(DEVICE_ONE),
            any(), any());
        assertEquals(List.of("real_data_01", "real_data"), tables.getAllValues());
    }

    @Test
    void fallsBackToLatestWindowWhenRequestedWindowIsEmpty() {
        LocalDateTime latest = LocalDateTime.of(2026, 7, 19, 15, 4, 11);
        LocalDateTime earliest = LocalDateTime.of(2026, 7, 19, 14, 50, 1);
        when(realDataMapper.selectTelemetry(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(List.of())
            .thenReturn(List.of(entity("2026-07-19 15:00:00")));
        when(realDataMapper.selectLatestCreateTime(anyString(), anyString(), anyString())).thenReturn(latest);
        when(realDataMapper.selectEarliestCreateTime(anyString(), anyString(), anyString())).thenReturn(earliest);

        TelemetryQueryResult result = service.queryTelemetry(DEVICE_ONE, DEVICE_ONE, WINDOW_START, WINDOW_END);

        assertTrue(result.fallbackToLatestData());
        // 回退窗口以最新记录为终点向前推默认窗口，但被表内最早记录约束
        assertEquals(latest.plusSeconds(1), result.endTime());
        assertEquals(earliest, result.startTime());
        assertEquals(1, result.quality().validRecordCount());
        verify(realDataMapper, times(2)).selectTelemetry(eq("real_data_01"), eq(DEVICE_ONE), eq(DEVICE_ONE),
            any(), any());
    }

    @Test
    void doesNotFallbackWhenWindowHasData() {
        when(realDataMapper.selectTelemetry(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(List.of(entity("2026-07-19 14:50:01")));

        TelemetryQueryResult result = service.queryTelemetry(DEVICE_ONE, DEVICE_ONE, WINDOW_START, WINDOW_END);

        assertFalse(result.fallbackToLatestData());
        verify(realDataMapper, never()).selectLatestCreateTime(anyString(), anyString(), anyString());
    }

    @Test
    void skipsFallbackWhenDisabled() {
        properties.setLatestDataFallbackEnabled(false);
        when(realDataMapper.selectTelemetry(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(List.of());

        TelemetryQueryResult result = service.queryTelemetry(DEVICE_ONE, DEVICE_ONE, WINDOW_START, WINDOW_END);

        assertFalse(result.fallbackToLatestData());
        assertEquals(0, result.quality().rawRecordCount());
        verify(realDataMapper, never()).selectLatestCreateTime(anyString(), anyString(), anyString());
    }

    @Test
    void emptyTableReturnsEmptyResultWithoutFallback() {
        when(realDataMapper.selectTelemetry(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(List.of());
        when(realDataMapper.selectLatestCreateTime(anyString(), anyString(), anyString())).thenReturn(null);

        TelemetryQueryResult result = service.queryTelemetry(DEVICE_ONE, DEVICE_ONE, WINDOW_START, WINDOW_END);

        assertFalse(result.fallbackToLatestData());
        assertEquals(0, result.quality().rawRecordCount());
        verify(realDataMapper, never()).selectEarliestCreateTime(anyString(), anyString(), anyString());
    }

    @Test
    void rejectsDeviceOutsideAllowList() {
        ServiceException exception = assertThrows(ServiceException.class,
            () -> service.queryTelemetry("未授权设备", "未授权设备", WINDOW_START, WINDOW_END));
        assertTrue(exception.getMessage().contains("当前无设备诊断权限"));
        verify(realDataMapper, never()).selectTelemetry(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void statisticsUseSameControlledTelemetryQuery() {
        RealDataEntity record = entity("2026-07-19 14:50:01");
        record.setDcVoltage(620F);
        when(realDataMapper.selectTelemetry(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(List.of(record));

        TelemetryStatisticsResult result = service.queryStatistics(DEVICE_ONE, DEVICE_ONE, WINDOW_START, WINDOW_END,
            List.of("dcVoltage"), List.of("avg", "count"));

        assertEquals(620D, result.metrics().get("dcVoltage").get("avg"));
        assertEquals(1L, result.metrics().get("dcVoltage").get("count"));
        verify(realDataMapper).selectTelemetry(eq("real_data_01"), eq(DEVICE_ONE), eq(DEVICE_ONE), any(), any());
    }

    @Test
    void rejectsInvalidStatisticsMetricBeforeQueryingDatabase() {
        ServiceException exception = assertThrows(ServiceException.class,
            () -> service.queryStatistics(DEVICE_ONE, DEVICE_ONE, WINDOW_START, WINDOW_END,
                List.of("madeUpMetric"), List.of("avg")));

        assertTrue(exception.getMessage().contains("不支持的遥测指标"));
        verify(realDataMapper, never()).selectTelemetry(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void seriesUseSameControlledTelemetryQuery() {
        RealDataEntity record = entity("2026-07-19 14:50:01");
        record.setMotorTemp(45F);
        when(realDataMapper.selectTelemetry(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(List.of(record));

        TelemetrySeriesResult result = service.querySeries(DEVICE_ONE, DEVICE_ONE, WINDOW_START, WINDOW_END,
            List.of("motorTemp"), 1);

        assertEquals(1, result.series().get("motorTemp").size());
        assertEquals(45D, result.series().get("motorTemp").get(0).value());
        verify(realDataMapper).selectTelemetry(eq("real_data_01"), eq(DEVICE_ONE), eq(DEVICE_ONE), any(), any());
    }

    @Test
    void reportSnapshotReusesOneTelemetryReadForSummaryStatisticsAndSeries() {
        properties.setNominalSamplingSeconds(60);
        RealDataEntity first = reportEntity("2026-07-19 14:50:00", 600F);
        RealDataEntity second = reportEntity("2026-07-19 14:51:00", 620F);
        when(realDataMapper.selectTelemetry(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(List.of(first, second));

        TelemetryReportSnapshot snapshot = service.queryReportTelemetry(
            DEVICE_ONE, DEVICE_ONE, WINDOW_START, WINDOW_START.plusMinutes(2));

        assertEquals(snapshot.telemetry().startTime(), snapshot.statistics().windowStart());
        assertEquals(snapshot.telemetry().endTime(), snapshot.series().windowEnd());
        assertEquals(610D, snapshot.statistics().metrics().get("dcVoltage").get("avg"));
        assertEquals(2, snapshot.series().series().get("dcVoltage").size());
        verify(realDataMapper, times(1)).selectTelemetry(eq("real_data_01"), eq(DEVICE_ONE), eq(DEVICE_ONE), any(), any());
    }

    @Test
    void reportSnapshotSkipsMetricsAndSeriesWhenDataQualityIsInsufficient() {
        when(realDataMapper.selectTelemetry(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(List.of(reportEntity("2026-07-19 14:50:00", 600F)));

        TelemetryReportSnapshot snapshot = service.queryReportTelemetry(
            DEVICE_ONE, DEVICE_ONE, WINDOW_START, WINDOW_END);

        assertFalse(snapshot.telemetry().quality().sufficient());
        assertEquals(null, snapshot.statistics());
        assertEquals(null, snapshot.series());
    }

    @Test
    void rejectsInvalidSeriesBucketBeforeQueryingDatabase() {
        ServiceException exception = assertThrows(ServiceException.class,
            () -> service.querySeries(DEVICE_ONE, DEVICE_ONE, WINDOW_START, WINDOW_END,
                List.of("motorTemp"), 0));

        assertTrue(exception.getMessage().contains("时间分桶分钟数必须大于0"));
        verify(realDataMapper, never()).selectTelemetry(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void rejectsInvalidTableNameInConfiguration() {
        properties.setDeviceTelemetryTables(Map.of(DEVICE_ONE, "bad_table; DROP TABLE x"));

        ServiceException exception = assertThrows(ServiceException.class,
            () -> service.queryTelemetry(DEVICE_ONE, DEVICE_ONE, WINDOW_START, WINDOW_END));
        assertTrue(exception.getMessage().contains("遥测表配置无效"));
        verify(realDataMapper, never()).selectTelemetry(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void resolvesSoleInverterForAllowedDevice() {
        when(realDataMapper.selectDistinctInverterNames("real_data_01", DEVICE_ONE)).thenReturn(List.of(DEVICE_ONE));

        assertEquals(DEVICE_ONE, service.resolveInverterName(DEVICE_ONE));
        verify(realDataMapper, never()).selectTelemetry(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void resolveIgnoresBlankInverterNames() {
        when(realDataMapper.selectDistinctInverterNames("real_data_01", DEVICE_ONE))
            .thenReturn(java.util.Arrays.asList(DEVICE_ONE, null, " "));

        assertEquals(DEVICE_ONE, service.resolveInverterName(DEVICE_ONE));
    }

    @Test
    void rejectsResolveWhenDeviceHasMultipleInverters() {
        when(realDataMapper.selectDistinctInverterNames("real_data_01", DEVICE_ONE))
            .thenReturn(List.of("逆变器1", "逆变器2"));

        ServiceException exception = assertThrows(ServiceException.class, () -> service.resolveInverterName(DEVICE_ONE));
        assertTrue(exception.getMessage().contains("逆变器1、逆变器2"));
    }

    @Test
    void rejectsResolveWhenDeviceHasNoTelemetry() {
        when(realDataMapper.selectDistinctInverterNames("real_data_01", DEVICE_ONE)).thenReturn(List.of());

        ServiceException exception = assertThrows(ServiceException.class, () -> service.resolveInverterName(DEVICE_ONE));
        assertTrue(exception.getMessage().contains("未找到设备"));
    }

    @Test
    void rejectsResolveForDeviceOutsideAllowList() {
        ServiceException exception = assertThrows(ServiceException.class, () -> service.resolveInverterName("未授权设备"));
        assertTrue(exception.getMessage().contains("当前无设备诊断权限"));
        verify(realDataMapper, never()).selectDistinctInverterNames(anyString(), anyString());
    }

    private static RealDataEntity entity(String timestamp) {
        RealDataEntity record = new RealDataEntity();
        record.setId(1L);
        record.setTimestamp(timestamp);
        record.setDeviceName(DEVICE_ONE);
        record.setInverterName(DEVICE_ONE);
        record.setStatus("42");
        record.setFaultCode("0");
        record.setAlarmCode("0");
        record.setMotorTemp(35F);
        record.setCreateTime(LocalDateTime.of(2026, 7, 19, 14, 50, 1));
        return record;
    }

    private static RealDataEntity reportEntity(String timestamp, Float dcVoltage) {
        RealDataEntity record = entity(timestamp);
        record.setDcVoltage(dcVoltage);
        record.setCurrentActual(dcVoltage / 10);
        record.setSpeedActual(1450F + dcVoltage / 100);
        record.setActualPower(dcVoltage / 100);
        record.setMotorTemp(40F + dcVoltage / 100);
        record.setInverterTemp(30F + dcVoltage / 100);
        record.setMotorLoadRate(60F + dcVoltage / 100);
        record.setInverterLoadRate(55F + dcVoltage / 100);
        return record;
    }

}
