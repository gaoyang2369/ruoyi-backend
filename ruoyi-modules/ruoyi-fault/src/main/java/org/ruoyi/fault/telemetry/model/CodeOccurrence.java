package org.ruoyi.fault.telemetry.model;

import java.time.LocalDateTime;

/**
 * 单个故障码或报警码在查询窗口内的出现情况。
 *
 * @param code 按 G120 规则归类后的代码
 * @param sampleCount 出现该代码的样本数
 * @param firstObservedAt 首次出现的业务时间
 * @param lastObservedAt 最近一次出现的业务时间
 * @param active 该代码在窗口内最后一条有效遥测中是否仍存在
 * @param recoveredAt 最后一次出现后首次确认该代码消失的业务时间；仍活动或无法确认时为 null
 */
public record CodeOccurrence(
    String code,
    int sampleCount,
    LocalDateTime firstObservedAt,
    LocalDateTime lastObservedAt,
    boolean active,
    LocalDateTime recoveredAt
) {

    /** 兼容既有调用方构造的纯窗口 occurrence；活动状态未确认。 */
    public CodeOccurrence(String code, int sampleCount, LocalDateTime firstObservedAt, LocalDateTime lastObservedAt) {
        this(code, sampleCount, firstObservedAt, lastObservedAt, false, null);
    }
}
