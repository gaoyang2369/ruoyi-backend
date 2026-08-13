package org.ruoyi.service.fault;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.domain.bo.fault.OperationReportGenerateBo;
import org.ruoyi.domain.enums.agent.AgentExecutionMode;
import org.ruoyi.domain.enums.agent.AgentScenarioCode;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.context.DiagnosisRequestContext;
import org.ruoyi.fault.report.OperationReportOrchestrator;
import org.ruoyi.fault.report.OperationReportResult;
import org.ruoyi.fault.telemetry.service.TelemetryQueryService;
import org.ruoyi.service.agent.IAgentService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * REST 入口的运行报告应用服务。
 * <p>
 * 负责 Agent 校验、逆变器确定性补全、默认时间窗与请求上下文组装，
 * 报告事实仍全部由 {@link OperationReportOrchestrator} 确定性产生。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperationReportService {

    private final IAgentService agentService;
    private final TelemetryQueryService telemetryQueryService;
    private final OperationReportOrchestrator operationReportOrchestrator;
    private final OperationReportNarrator operationReportNarrator;
    private final FaultDiagnosisProperties faultDiagnosisProperties;
    private final OperationReportSnapshotService operationReportSnapshotService;

    public OperationReportResult generate(OperationReportGenerateBo bo, Long userId, String tenantId) {
        AgentVo agent = loadAndValidateAgent(bo.getAgentId());
        String deviceName = bo.getDeviceName().trim();
        String inverterName = StringUtils.isNotBlank(bo.getInverterName())
            ? bo.getInverterName().trim() : telemetryQueryService.resolveInverterName(deviceName);
        TimeRange range = timeRange(bo.getStartTime(), bo.getEndTime());
        DiagnosisCommand command = new DiagnosisCommand(deviceName, inverterName, range.start(), range.end(),
            null, agent.getKnowledgeIds() == null ? List.of() : List.copyOf(agent.getKnowledgeIds()),
            new DiagnosisRequestContext(agent.getId(), null, userId, tenantId, UUID.randomUUID().toString()));
        OperationReportResult facts = operationReportOrchestrator.generate(command);
        OperationReportResult report = facts.withNarrative(narrate(agent.getId(), facts));
        return operationReportSnapshotService.save(report, bo.getSessionId(), userId, tenantId);
    }

    public OperationReportResult get(String reportCode, Long userId, String tenantId) {
        return operationReportSnapshotService.get(reportCode, userId, tenantId);
    }

    /** 报告叙事固定经 Hermes；agentId 仅保留为现有聊天报告入口的兼容参数。 */
    public OperationReportResult.ReportNarrative narrate(Long agentId, OperationReportResult report) {
        return operationReportNarrator.narrate(report);
    }

    private AgentVo loadAndValidateAgent(Long agentId) {
        AgentVo agent = agentService.queryById(agentId);
        if (agent == null || agent.getId() == null) {
            throw new ServiceException("故障诊断Agent不存在");
        }
        if (!"0".equals(agent.getStatus())) {
            throw new ServiceException("故障诊断Agent未启用: " + agent.getId());
        }
        if (!AgentScenarioCode.FAULT_DIAGNOSIS.name().equals(agent.getScenarioCode())) {
            throw new ServiceException("Agent不是故障诊断场景: " + agent.getId());
        }
        if (!AgentExecutionMode.DETERMINISTIC.name().equals(agent.getExecutionMode())) {
            throw new ServiceException("故障诊断Agent执行方式必须为DETERMINISTIC: " + agent.getId());
        }
        return agent;
    }

    /** 起止时间必须同时提供；均为空时使用默认窗口（最近 N 分钟）。 */
    private TimeRange timeRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime != null && endTime != null) {
            return new TimeRange(startTime, endTime);
        }
        if (startTime != null || endTime != null) {
            throw new ServiceException("运行报告开始时间和结束时间必须同时提供");
        }
        LocalDateTime end = LocalDateTime.now(ZoneId.of(faultDiagnosisProperties.getTimezone()));
        return new TimeRange(end.minusMinutes(faultDiagnosisProperties.getDefaultWindowMinutes()), end);
    }

    private record TimeRange(LocalDateTime start, LocalDateTime end) {
    }

}
