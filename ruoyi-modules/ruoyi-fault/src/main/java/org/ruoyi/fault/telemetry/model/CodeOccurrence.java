package org.ruoyi.fault.telemetry.model;

import java.time.LocalDateTime;

/**
 * 单个故障码或报警码在查询窗口内的出现情况。
 *
 * @param code 按 G120 规则归类后的代码
 * @param sampleCount 出现该代码的样本数
 * @param firstObservedAt 首次出现的业务时间
 * @param lastObservedAt 最近一次出现的业务时间
 */
public record CodeOccurrence(
    String code,
    int sampleCount,
    LocalDateTime firstObservedAt,
    LocalDateTime lastObservedAt
) {
}
