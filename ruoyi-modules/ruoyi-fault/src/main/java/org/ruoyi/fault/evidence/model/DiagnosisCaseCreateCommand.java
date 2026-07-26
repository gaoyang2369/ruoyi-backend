package org.ruoyi.fault.evidence.model;

import java.time.LocalDateTime;

/** 创建诊断案例的输入。 */
public record DiagnosisCaseCreateCommand(
    Long sessionId,
    Long agentId,
    Long userId,
    String assetCode,
    String question,
    LocalDateTime queryStartTime,
    LocalDateTime queryEndTime
) {
}
