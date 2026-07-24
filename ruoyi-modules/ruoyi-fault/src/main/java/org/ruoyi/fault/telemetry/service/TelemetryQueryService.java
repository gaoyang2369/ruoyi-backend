package org.ruoyi.fault.telemetry.service;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.telemetry.analysis.TelemetryDataAnalyzer;
import org.ruoyi.fault.telemetry.entity.RealDataEntity;
import org.ruoyi.fault.telemetry.mapper.RealDataMapper;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.List;
import java.util.Objects;

/**
 * 受控运行数据查询服务。
 * <p>
 * 该类是故障诊断读取 {@code real_data} 的唯一业务入口，只接收设备、逆变器和时间窗，
 * 不接收 SQL，也不向调用方返回原始时序行。职责仅包括请求校验、固定 Mapper 查询和结果分析调度；
 * 时间解析、去重与摘要计算由 {@link TelemetryDataAnalyzer} 完成。
 */
@Service
@RequiredArgsConstructor
public class TelemetryQueryService {

    private final RealDataMapper realDataMapper;
    private final TelemetryDataAnalyzer telemetryDataAnalyzer;
    private final FaultDiagnosisProperties properties;

    /**
     * 查询一个受授权资产在指定窗口内的遥测诊断摘要。
     *
     * @param deviceName 设备名称，同时作为当前阶段的资产编码
     * @param inverterName 逆变器名称
     * @param startTime 精确查询起点，包含该时刻
     * @param endTime 精确查询终点，不包含该时刻
     * @return 仅包含质量、故障码、关键事件、统计值和来源摘要的聚合结果
     */
    public TelemetryQueryResult queryTelemetry(String deviceName, String inverterName,
                                                LocalDateTime startTime, LocalDateTime endTime) {
        String normalizedDeviceName = normalizeRequiredText(deviceName, "设备名称不能为空");
        String normalizedInverterName = normalizeRequiredText(inverterName, "逆变器名称不能为空");
        validateRequest(normalizedDeviceName, startTime, endTime);

        // create_time 仅用于数据库粗筛。缓冲区用于容纳入库延迟，精确窗口仍由 observedAt 决定。
        int bufferSeconds = properties.getCreateTimeBufferSeconds();
        LocalDateTime queryStart = startTime.minusSeconds(bufferSeconds);
        LocalDateTime queryEnd = endTime.plusSeconds(bufferSeconds);
        List<RealDataEntity> rawRecords = realDataMapper.selectTelemetry(
            normalizedDeviceName, normalizedInverterName, queryStart, queryEnd
        );

        return telemetryDataAnalyzer.analyze(normalizedDeviceName, normalizedInverterName, startTime, endTime,
            rawRecords == null ? List.of() : rawRecords);
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

}
