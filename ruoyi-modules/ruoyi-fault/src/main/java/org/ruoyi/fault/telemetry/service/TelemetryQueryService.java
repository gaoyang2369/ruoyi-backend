package org.ruoyi.fault.telemetry.service;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.telemetry.analysis.TelemetryDataAnalyzer;
import org.ruoyi.fault.telemetry.entity.RealDataEntity;
import org.ruoyi.fault.telemetry.mapper.RealDataMapper;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.model.TelemetryReportSnapshot;
import org.ruoyi.fault.telemetry.model.TelemetryStatisticsResult;
import org.ruoyi.fault.telemetry.model.TelemetrySeriesResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 受控运行数据查询服务。
 * <p>
 * 该类是故障诊断读取遥测数据的唯一业务入口，只接收设备、逆变器和时间窗，
 * 不接收 SQL，也不向调用方返回原始时序行。职责仅包括请求校验、受控表路由、
 * 固定 Mapper 查询、最新数据回退和结果分析调度；时间解析、去重与摘要计算由
 * {@link TelemetryDataAnalyzer} 完成。
 */
@Service
@RequiredArgsConstructor
public class TelemetryQueryService {

    /** 受控表名只允许字母、数字和下划线，配置值在查询前强制校验，杜绝拼接注入。 */
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");
    /** 与 telemetry/series 工具接口的既有默认分桶口径一致。 */
    private static final int REPORT_SERIES_BUCKET_MINUTES = 1;
    private static final List<String> REPORT_METRICS = List.of(
        "dcVoltage", "currentActual", "speedActual", "actualPower",
        "motorTemp", "inverterTemp", "motorLoadRate", "inverterLoadRate");

    private final RealDataMapper realDataMapper;
    private final TelemetryDataAnalyzer telemetryDataAnalyzer;
    private final FaultDiagnosisProperties properties;

    /**
     * 查询一个受授权资产在指定窗口内的遥测诊断摘要。
     * <p>
     * 请求窗口查不到任何数据且开启了最新数据回退时，自动改用该设备最近可用的
     * 数据窗口重新查询，并在结果中标记 {@code fallbackToLatestData}。
     *
     * @param deviceName 设备名称，同时作为当前阶段的资产编码
     * @param inverterName 逆变器名称
     * @param startTime 精确查询起点，包含该时刻
     * @param endTime 精确查询终点，不包含该时刻
     * @return 仅包含质量、故障码、关键事件、统计值和来源摘要的聚合结果
     */
    public TelemetryQueryResult queryTelemetry(String deviceName, String inverterName,
                                                LocalDateTime startTime, LocalDateTime endTime) {
        QueriedTelemetry telemetry = loadTelemetry(deviceName, inverterName, startTime, endTime);
        TelemetryQueryResult result = telemetryDataAnalyzer.analyze(
            telemetry.deviceName(), telemetry.inverterName(), telemetry.startTime(), telemetry.endTime(),
            telemetry.rawRecords());
        return telemetry.fallbackUsed() ? result.withFallbackToLatestData(true) : result;
    }

    /**
     * 读取一次受控遥测数据并生成报告所需的快照、通用统计和降采样趋势。
     * <p>
     * 统计与趋势直接复用同一次 {@link TelemetryDataAnalyzer} 分析的过滤和去重结果，
     * 不调用 HTTP 接口，也不会再次访问遥测表。
     */
    public TelemetryReportSnapshot queryReportTelemetry(String deviceName, String inverterName,
                                                        LocalDateTime startTime, LocalDateTime endTime) {
        QueriedTelemetry telemetry = loadTelemetry(deviceName, inverterName, startTime, endTime);
        TelemetryReportSnapshot analysis = telemetryDataAnalyzer.analyzeReport(
            telemetry.deviceName(), telemetry.inverterName(), telemetry.startTime(), telemetry.endTime(),
            telemetry.rawRecords(), REPORT_METRICS, REPORT_SERIES_BUCKET_MINUTES);
        TelemetryQueryResult snapshot = telemetry.fallbackUsed()
            ? analysis.telemetry().withFallbackToLatestData(true) : analysis.telemetry();
        return new TelemetryReportSnapshot(snapshot, analysis.statistics(), analysis.series(), analysis.analysisSamples());
    }

    /**
     * 查询一个受授权资产在指定窗口内的指定数值指标统计。
     * <p>
     * 数据读取、最新数据回退、业务时间过滤、去重和质量统计与 {@link #queryTelemetry} 复用同一条链路。
     */
    public TelemetryStatisticsResult queryStatistics(String deviceName, String inverterName,
                                                      LocalDateTime startTime, LocalDateTime endTime,
                                                      List<String> metrics, List<String> aggregations) {
        validateStatisticsRequest(metrics, aggregations);
        QueriedTelemetry telemetry = loadTelemetry(deviceName, inverterName, startTime, endTime);
        return telemetryDataAnalyzer.analyzeStatistics(
            telemetry.deviceName(), telemetry.inverterName(), telemetry.startTime(), telemetry.endTime(),
            telemetry.rawRecords(), metrics, aggregations);
    }

    /** 校验内部工具统计请求支持的指标和统计方式。 */
    public void validateStatisticsRequest(List<String> metrics, List<String> aggregations) {
        telemetryDataAnalyzer.validateStatisticsRequest(metrics, aggregations);
    }

    /** 查询一个受授权资产在指定窗口内的降采样遥测时序。 */
    public TelemetrySeriesResult querySeries(String deviceName, String inverterName,
                                              LocalDateTime startTime, LocalDateTime endTime,
                                              List<String> metrics, int bucketMinutes) {
        validateSeriesRequest(metrics, bucketMinutes);
        QueriedTelemetry telemetry = loadTelemetry(deviceName, inverterName, startTime, endTime);
        return telemetryDataAnalyzer.analyzeSeries(
            telemetry.deviceName(), telemetry.inverterName(), telemetry.startTime(), telemetry.endTime(),
            telemetry.rawRecords(), metrics, bucketMinutes);
    }

    /** 校验内部工具时序请求支持的指标和分桶长度。 */
    public void validateSeriesRequest(List<String> metrics, int bucketMinutes) {
        telemetryDataAnalyzer.validateSeriesRequest(metrics, bucketMinutes);
    }

    private QueriedTelemetry loadTelemetry(String deviceName, String inverterName,
                                           LocalDateTime startTime, LocalDateTime endTime) {
        String normalizedDeviceName = normalizeAssetIdentifier(deviceName, "设备名称不能为空");
        String normalizedInverterName = normalizeAssetIdentifier(inverterName, "逆变器名称不能为空");
        validateRequest(normalizedDeviceName, startTime, endTime);

        String tableName = resolveTable(normalizedDeviceName);
        int bufferSeconds = properties.getCreateTimeBufferSeconds();
        // create_time 仅用于数据库粗筛。缓冲区用于容纳入库延迟，精确窗口仍由 observedAt 决定。
        List<RealDataEntity> rawRecords = realDataMapper.selectTelemetry(
            tableName, normalizedDeviceName, normalizedInverterName,
            startTime.minusSeconds(bufferSeconds), endTime.plusSeconds(bufferSeconds)
        );

        boolean fallbackUsed = false;
        LocalDateTime effectiveStart = startTime;
        LocalDateTime effectiveEnd = endTime;
        if ((rawRecords == null || rawRecords.isEmpty()) && properties.isLatestDataFallbackEnabled()) {
            LocalDateTime latest = realDataMapper.selectLatestCreateTime(
                tableName, normalizedDeviceName, normalizedInverterName);
            if (latest != null) {
                LocalDateTime earliest = realDataMapper.selectEarliestCreateTime(
                    tableName, normalizedDeviceName, normalizedInverterName);
                // 回退窗口以最新一条记录为终点，向前取默认窗口长度，并不早于表内最早记录。
                effectiveEnd = latest.plusSeconds(1);
                effectiveStart = effectiveEnd.minusMinutes(properties.getDefaultWindowMinutes());
                if (earliest != null && earliest.isAfter(effectiveStart)) {
                    effectiveStart = earliest;
                }
                rawRecords = realDataMapper.selectTelemetry(
                    tableName, normalizedDeviceName, normalizedInverterName,
                    effectiveStart.minusSeconds(bufferSeconds), effectiveEnd.plusSeconds(bufferSeconds)
                );
                fallbackUsed = true;
            }
        }

        return new QueriedTelemetry(normalizedDeviceName, normalizedInverterName, effectiveStart, effectiveEnd,
            rawRecords == null ? List.of() : rawRecords, fallbackUsed);
    }

    /**
     * 解析设备在遥测数据中唯一的逆变器名称，用于用户未指明逆变器时的确定性补全。
     * <p>
     * 仅查询受控表中该设备出现过的逆变器名：唯一时直接返回；无数据或存在多个
     * 逆变器时抛出业务异常，由调用方决定如何向用户表达。校验顺序与诊断查询一致，
     * 未授权设备不会触达遥测表。
     */
    public String resolveInverterName(String deviceName) {
        String normalizedDeviceName = normalizeAssetIdentifier(deviceName, "设备名称不能为空");
        if (!properties.isEnabled()) {
            throw new ServiceException("故障诊断功能未启用");
        }
        validateConfiguration();
        validateAssetAllowed(normalizedDeviceName);
        String tableName = resolveTable(normalizedDeviceName);
        List<String> names = realDataMapper.selectDistinctInverterNames(tableName, normalizedDeviceName);
        List<String> distinct = names == null ? List.of() : names.stream()
            .filter(StringUtils::hasText).map(String::trim).distinct().toList();
        if (distinct.isEmpty()) {
            throw new ServiceException("未找到设备 " + normalizedDeviceName + " 的遥测数据，请确认设备名称是否正确");
        }
        if (distinct.size() > 1) {
            throw new ServiceException("设备 " + normalizedDeviceName + " 下存在多个逆变器（"
                + String.join("、", distinct) + "），请在问题中指明要诊断的逆变器");
        }
        return distinct.get(0);
    }

    /**
     * 按设备白名单路由遥测表：模拟数据阶段每台设备使用专属表，
     * 未配置专属表的设备回退到默认表。表名只可能来自配置，且在查询前强校验。
     */
    private String resolveTable(String deviceName) {
        Map<String, String> deviceTables = properties.getDeviceTelemetryTables();
        String tableName = deviceTables == null ? null : deviceTables.get(deviceName);
        if (!StringUtils.hasText(tableName)) {
            tableName = properties.getTelemetryTable();
        }
        if (!StringUtils.hasText(tableName) || !TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            throw new ServiceException("故障诊断遥测表配置无效: " + tableName);
        }
        return tableName;
    }

    /**
     * 校验功能开关、查询窗口、资产白名单及显式时区配置。
     * <p>
     * 当前仓库还没有用户与设备的授权关系表，因此第一阶段以 allowed-assets 作为后端资产白名单。
     */
    private void validateRequest(String deviceName, LocalDateTime startTime, LocalDateTime endTime) {
        if (!properties.isEnabled()) {
            throw new ServiceException("故障诊断功能未启用");
        }
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new ServiceException("诊断时间范围无效");
        }
        validateConfiguration();
        if (Duration.between(startTime, endTime).compareTo(Duration.ofMinutes(properties.getMaxWindowMinutes())) > 0) {
            throw new ServiceException("诊断时间范围超过最大允许窗口");
        }
        validateAssetAllowed(deviceName);
    }

    private void validateAssetAllowed(String deviceName) {
        if (properties.getAllowedAssets() == null || properties.getAllowedAssets().stream()
            .filter(Objects::nonNull).map(String::trim).noneMatch(deviceName::equals)) {
            throw new ServiceException("当前无设备诊断权限: " + deviceName);
        }
    }

    /**
     * 对会影响查询边界和质量判断的配置做失败前置检查，避免使用隐含或非法默认值继续诊断。
     */
    private void validateConfiguration() {
        if (properties.getDefaultWindowMinutes() <= 0
            || properties.getMaxWindowMinutes() <= 0
            || properties.getDefaultWindowMinutes() > properties.getMaxWindowMinutes()
            || properties.getNominalSamplingSeconds() <= 0
            || properties.getCreateTimeBufferSeconds() < 0
            || properties.getCompletenessThreshold() < 0D
            || properties.getCompletenessThreshold() > 1D
            || !StringUtils.hasText(properties.getTimezone())) {
            throw new ServiceException("故障诊断查询配置无效");
        }
        try {
            ZoneId.of(properties.getTimezone());
        } catch (ZoneRulesException e) {
            throw new ServiceException("故障诊断时区配置无效: " + properties.getTimezone());
        }
        if (!StringUtils.hasText(properties.getTelemetryTable())
            || !TABLE_NAME_PATTERN.matcher(properties.getTelemetryTable()).matches()) {
            throw new ServiceException("故障诊断遥测表配置无效: " + properties.getTelemetryTable());
        }
        Map<String, String> deviceTables = properties.getDeviceTelemetryTables();
        if (deviceTables != null) {
            for (Map.Entry<String, String> entry : deviceTables.entrySet()) {
                String table = entry.getValue();
                if (!StringUtils.hasText(table) || !TABLE_NAME_PATTERN.matcher(table).matches()) {
                    throw new ServiceException("故障诊断遥测表配置无效: " + table);
                }
            }
        }
    }

    /**
     * 统一清理外部字符串输入，避免前后空格导致权限判断和数据库条件不一致。
     */
    private String normalizeRequiredText(String value, String errorMessage) {
        if (!StringUtils.hasText(value)) {
            throw new ServiceException(errorMessage);
        }
        return value.trim();
    }

    /**
     * 将语音识别、模型输出中常见的中文数字统一为遥测资产使用的阿拉伯数字。
     * <p>
     * 白名单校验、表路由和 Mapper 查询都必须使用同一个规范名称；否则例如
     * {@code G120电机一} 虽指向 {@code G120电机1}，仍会在白名单阶段被误判为未授权。
     */
    private String normalizeAssetIdentifier(String value, String errorMessage) {
        String trimmed = normalizeRequiredText(value, errorMessage);
        StringBuilder normalized = new StringBuilder(trimmed.length());
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            normalized.append(switch (character) {
                case '〇', '零' -> '0';
                case '一' -> '1';
                case '二', '两' -> '2';
                case '三' -> '3';
                case '四' -> '4';
                case '五' -> '5';
                case '六' -> '6';
                case '七' -> '7';
                case '八' -> '8';
                case '九' -> '9';
                case '０' -> '0';
                case '１' -> '1';
                case '２' -> '2';
                case '３' -> '3';
                case '４' -> '4';
                case '５' -> '5';
                case '６' -> '6';
                case '７' -> '7';
                case '８' -> '8';
                case '９' -> '9';
                default -> character;
            });
        }
        return normalized.toString();
    }

    /** 统一遥测数据读取链路的受控查询结果。 */
    private record QueriedTelemetry(String deviceName, String inverterName, LocalDateTime startTime,
                                    LocalDateTime endTime, List<RealDataEntity> rawRecords, boolean fallbackUsed) {
    }

}
