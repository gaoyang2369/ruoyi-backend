package org.ruoyi.fault.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 确定性故障诊断工具请求。 */
public record FaultDiagnosisRequest(
    String deviceName,
    String inverterName,
    LocalDateTime startTime,
    LocalDateTime endTime,
    Integer recentMinutes,
    String symptom,
    List<Long> knowledgeBaseIds,
    FaultDiagnosisContextRequest context
) {
}
