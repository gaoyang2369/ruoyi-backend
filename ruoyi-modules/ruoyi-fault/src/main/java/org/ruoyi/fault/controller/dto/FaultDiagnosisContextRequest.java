package org.ruoyi.fault.controller.dto;

/** 由内部调用方传入的诊断请求上下文。 */
public record FaultDiagnosisContextRequest(
    Long agentId,
    Long sessionId,
    Long userId,
    String tenantId,
    String requestId
) {
}
