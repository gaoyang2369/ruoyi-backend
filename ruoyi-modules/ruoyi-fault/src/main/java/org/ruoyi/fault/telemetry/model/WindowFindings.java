package org.ruoyi.fault.telemetry.model;

import java.util.List;

/** 查询窗口内出现过的代码集合；不表示这些代码在窗口结束时仍然活动。 */
public record WindowFindings(
    List<String> faultCodes,
    List<String> alarmCodes
) {
    public WindowFindings {
        faultCodes = faultCodes == null ? List.of() : List.copyOf(faultCodes);
        alarmCodes = alarmCodes == null ? List.of() : List.copyOf(alarmCodes);
    }
}
