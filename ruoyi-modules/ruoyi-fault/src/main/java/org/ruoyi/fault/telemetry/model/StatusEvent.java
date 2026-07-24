package org.ruoyi.fault.telemetry.model;

import java.time.LocalDateTime;

/**
 * 状态、故障码或报警码发生变化时记录的关键事件。
 */
public record StatusEvent(
    LocalDateTime observedAt,
    String status,
    String faultCode,
    String alarmCode
) {
}
