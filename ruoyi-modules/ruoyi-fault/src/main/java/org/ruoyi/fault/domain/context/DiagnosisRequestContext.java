package org.ruoyi.fault.domain.context;

/** 已由上层认证和 Agent 配置提供的诊断请求上下文。 */
public record DiagnosisRequestContext(
    Long agentId,
    Long sessionId,
    Long userId,
    String tenantId,
    String requestId
) {
}
