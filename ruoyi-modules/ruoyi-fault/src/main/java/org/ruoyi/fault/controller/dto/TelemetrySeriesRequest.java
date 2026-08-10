package org.ruoyi.fault.controller.dto;

import java.util.List;

/** 遥测指标趋势工具请求。 */
public record TelemetrySeriesRequest(
    String deviceName,
    String inverterName,
    Integer windowMinutes,
    List<String> metrics,
    Integer bucketMinutes
) {
}
