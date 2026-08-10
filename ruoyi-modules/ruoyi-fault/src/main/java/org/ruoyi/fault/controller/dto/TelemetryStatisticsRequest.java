package org.ruoyi.fault.controller.dto;

import java.util.List;

/** 遥测数值指标统计工具请求。 */
public record TelemetryStatisticsRequest(
    String deviceName,
    String inverterName,
    Integer windowMinutes,
    List<String> metrics,
    List<String> aggregations
) {
}
