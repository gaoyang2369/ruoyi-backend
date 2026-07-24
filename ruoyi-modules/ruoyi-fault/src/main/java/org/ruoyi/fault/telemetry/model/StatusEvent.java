package org.ruoyi.fault.telemetry.model;

import java.time.LocalDateTime;

/**
 * 状态、故障码或报警码发生变化时记录的关键事件。
 *
 * @param observedAt 事件业务时间
 * @param status 运行状态
 * @param faultCode 故障码
 * @param alarmCode 报警码
 */
public record StatusEvent(
    LocalDateTime observedAt,
    String status,
    String faultCode,
    String alarmCode
) {
}
