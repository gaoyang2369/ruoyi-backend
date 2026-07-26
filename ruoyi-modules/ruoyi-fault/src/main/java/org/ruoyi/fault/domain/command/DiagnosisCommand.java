package org.ruoyi.fault.domain.command;

import org.ruoyi.fault.domain.context.DiagnosisRequestContext;

import java.time.LocalDateTime;
import java.util.List;

/** 确定性故障诊断的输入；知识库范围只能来自已绑定的 Agent。 */
public record DiagnosisCommand(
    String deviceName,
    String inverterName,
    LocalDateTime startTime,
    LocalDateTime endTime,
    String symptom,
    List<Long> knowledgeBaseIds,
    DiagnosisRequestContext context
) {
}
