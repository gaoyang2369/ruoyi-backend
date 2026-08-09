package org.ruoyi.fault.controller.dto;

import java.time.LocalDateTime;

/** 设备状态工具请求。 */
public record FaultStatusRequest(
    String deviceName,
    String inverterName,
    LocalDateTime startTime,
    LocalDateTime endTime,
    Integer recentMinutes
) {
}
