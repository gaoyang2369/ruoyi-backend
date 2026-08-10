package org.ruoyi.fault.controller;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.fault.application.FaultCodeKnowledgeQueryService;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.controller.dto.FaultCodeRequest;
import org.ruoyi.fault.controller.dto.FaultDiagnosisContextRequest;
import org.ruoyi.fault.controller.dto.FaultDiagnosisRequest;
import org.ruoyi.fault.controller.dto.FaultStatusRequest;
import org.ruoyi.fault.diagnosis.FaultDiagnosisOrchestrator;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.context.DiagnosisRequestContext;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.ruoyi.fault.telemetry.service.TelemetryQueryService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Hermes 等内部工具调用方使用的故障能力 HTTP 适配层。
 * 仅负责请求 DTO、现有服务与响应 DTO 之间的映射，不执行 Agent 工作。
 */
@RestController
@RequestMapping("/internal/fault-tools")
@RequiredArgsConstructor
public class FaultToolController {

    private final TelemetryQueryService telemetryQueryService;
    private final FaultCodeKnowledgeQueryService faultCodeKnowledgeQueryService;
    private final FaultDiagnosisOrchestrator faultDiagnosisOrchestrator;
    private final FaultDiagnosisProperties faultDiagnosisProperties;

    @PostMapping("/status")
    public R<TelemetryQueryResult> status(@RequestBody FaultStatusRequest request) {
        TimeRange range = resolveTimeRange(request.startTime(), request.endTime(), request.recentMinutes());
        String inverterName = resolveInverterName(request.deviceName(), request.inverterName());
        TelemetryQueryResult result = telemetryQueryService.queryTelemetry(
            request.deviceName(), inverterName, range.startTime(), range.endTime());
        return R.ok(result);
    }

    @PostMapping("/fault-code")
    public R<FaultKnowledgeResult> faultCode(@RequestBody FaultCodeRequest request) {
        FaultKnowledgeResult result = faultCodeKnowledgeQueryService.query(
            request.code(), request.knowledgeBaseIds());
        return R.ok(result);
    }

    @PostMapping("/diagnose")
    public R<DiagnosisResult> diagnose(@RequestBody FaultDiagnosisRequest request) {
        DiagnosisResult result = faultDiagnosisOrchestrator.diagnose(buildDiagnosisCommand(request));
        return R.ok(result);
    }

    private DiagnosisCommand buildDiagnosisCommand(FaultDiagnosisRequest request) {
        TimeRange range = resolveTimeRange(request.startTime(), request.endTime(), request.recentMinutes());
        String inverterName = resolveInverterName(request.deviceName(), request.inverterName());
        return new DiagnosisCommand(request.deviceName(), inverterName, range.startTime(), range.endTime(),
            request.symptom(), request.knowledgeBaseIds(), buildContext(request.context()));
    }

    /**
     * 工具调用方可以只提供用户可识别的设备名；逆变器名由受控遥测表确定性补全。
     * 显式提供时保留调用方选择，避免多逆变器设备被错误归并。
     */
    private String resolveInverterName(String deviceName, String inverterName) {
        return StringUtils.hasText(inverterName)
            ? inverterName.trim()
            : telemetryQueryService.resolveInverterName(deviceName);
    }

    private DiagnosisRequestContext buildContext(FaultDiagnosisContextRequest context) {
        if (context == null) {
            return null;
        }
        return new DiagnosisRequestContext(context.agentId(), context.sessionId(), context.userId(),
            context.tenantId(), context.requestId());
    }

    private TimeRange resolveTimeRange(LocalDateTime startTime, LocalDateTime endTime, Integer recentMinutes) {
        if (startTime != null || endTime != null) {
            if (startTime == null || endTime == null) {
                throw new ServiceException("诊断开始时间和结束时间必须同时提供");
            }
            return new TimeRange(startTime, endTime);
        }
        int windowMinutes = recentMinutes == null
            ? faultDiagnosisProperties.getDefaultWindowMinutes() : recentMinutes;
        if (windowMinutes <= 0) {
            throw new ServiceException("最近分钟数必须大于0");
        }
        LocalDateTime end = LocalDateTime.now(ZoneId.of(faultDiagnosisProperties.getTimezone()));
        return new TimeRange(end.minusMinutes(windowMinutes), end);
    }

    private record TimeRange(LocalDateTime startTime, LocalDateTime endTime) {
    }
}
