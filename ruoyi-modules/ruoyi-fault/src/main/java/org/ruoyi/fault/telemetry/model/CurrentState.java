package org.ruoyi.fault.telemetry.model;

import java.time.LocalDateTime;
import java.util.List;

/** 实际分析窗口末尾可确认的最新遥测状态，不等同于窗口内曾出现过的代码。 */
public record CurrentState(
    String status,
    String statusCode,
    LocalDateTime observedAt,
    List<String> activeFaultCodes,
    List<String> activeAlarmCodes
) {
    public CurrentState {
        activeFaultCodes = activeFaultCodes == null ? List.of() : List.copyOf(activeFaultCodes);
        activeAlarmCodes = activeAlarmCodes == null ? List.of() : List.copyOf(activeAlarmCodes);
    }
}
