package org.ruoyi.fault.telemetry.model;

import java.time.LocalDateTime;

/**
 * 状态、故障码或报警码发生变化时记录的关键事件。
 *
 * @param observedAt 事件业务时间
 * @param status 运行状态
 * @param faultCode 故障码
 * @param alarmCode 报警码
 * @param previousObservedAt 上一个有效遥测样本的业务时间；仅供消费方精确闭合前一状态区间
 */
public record StatusEvent(
    LocalDateTime observedAt,
    String status,
    String faultCode,
    String alarmCode,
    LocalDateTime previousObservedAt
) {

    /** 兼容既有调用方构造的状态变化事件。 */
    public StatusEvent(LocalDateTime observedAt, String status, String faultCode, String alarmCode) {
        this(observedAt, status, faultCode, alarmCode, null);
    }
}
