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
import org.ruoyi.fault.report.PreparedOperationReport;
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
        OperationReportResult facts = prepareSnapshot(bo, userId, tenantId);
        // 旧网页入口保持同步生成体验；narrator 仅是新 prepare/finalize 调用链未接入时的兼容回退。
        OperationReportResult.ReportNarrative narrative = narrate(bo.getAgentId(), facts);
        return completePrepared(facts.metadata().reportId(), narrative, userId, tenantId);
    }

    /** prepareOperationReport：一次查询并保存确定性事实，返回给 Hermes 的精简 payload。 */
    public PreparedOperationReport prepare(OperationReportGenerateBo bo, Long userId, String tenantId) {
        return PreparedOperationReport.from(prepareSnapshot(bo, userId, tenantId));
    }

    /** 聊天兼容入口可直接提供已规范化的 command，仍只生成并保存一次快照。 */
    public OperationReportResult prepare(DiagnosisCommand command, Long sessionId, Long userId, String tenantId) {
        OperationReportResult facts = operationReportOrchestrator.generate(command);
        operationReportSnapshotService.prepare(facts, sessionId, userId, tenantId);
        return facts;
    }

    /** finalizeOperationReport：只校验并写回 prepare 阶段的同一份快照。 */
    public OperationReportResult finalize(String reportId, OperationReportResult.ReportNarrative narrative,
                                          Long userId, String tenantId) {
        if (narrative == null) {
            throw new ServiceException("运行报告叙事不能为空");
        }
        OperationReportResult prepared = operationReportSnapshotService.get(reportId, userId, tenantId);
        operationReportNarrator.validateNarrative(prepared, narrative);
        return operationReportSnapshotService.finalize(reportId, narrative, userId, tenantId);
    }

    /** 仅供旧同步入口在 Hermes 不可用时完成确定性快照，不用于 Internal finalize tool。 */
    public OperationReportResult completeFallback(String reportId, Long userId, String tenantId) {
        return operationReportSnapshotService.finalize(reportId, null, userId, tenantId);
    }

    private OperationReportResult prepareSnapshot(OperationReportGenerateBo bo, Long userId, String tenantId) {
        AgentVo agent = loadAndValidateAgent(bo.getAgentId());
        String deviceName = bo.getDeviceName().trim();
        String inverterName = StringUtils.isNotBlank(bo.getInverterName())
            ? bo.getInverterName().trim() : telemetryQueryService.resolveInverterName(deviceName);
        TimeRange range = timeRange(bo.getStartTime(), bo.getEndTime(), bo.getRecentMinutes());
        DiagnosisCommand command = new DiagnosisCommand(deviceName, inverterName, range.start(), range.end(),
            null, agent.getKnowledgeIds() == null ? List.of() : List.copyOf(agent.getKnowledgeIds()),
            new DiagnosisRequestContext(agent.getId(), null, userId, tenantId, UUID.randomUUID().toString()));
        return prepare(command, bo.getSessionId(), userId, tenantId);
    }

    /** narrator 失败时仍允许旧入口完成确定性报告；内部 finalize 则要求有效 narrative。 */
    private OperationReportResult completePrepared(String reportId, OperationReportResult.ReportNarrative narrative,
                                                   Long userId, String tenantId) {
        if (narrative == null) {
            return completeFallback(reportId, userId, tenantId);
        }
        return finalize(reportId, narrative, userId, tenantId);
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
    private TimeRange timeRange(LocalDateTime startTime, LocalDateTime endTime, Integer recentMinutes) {
        if (startTime != null && endTime != null) {
            return new TimeRange(startTime, endTime);
        }
        if (startTime != null || endTime != null) {
            throw new ServiceException("运行报告开始时间和结束时间必须同时提供");
        }
        int windowMinutes = recentMinutes == null ? faultDiagnosisProperties.getDefaultWindowMinutes() : recentMinutes;
        if (windowMinutes <= 0) {
            throw new ServiceException("最近分钟数必须大于0");
        }
        LocalDateTime end = LocalDateTime.now(ZoneId.of(faultDiagnosisProperties.getTimezone()));
        return new TimeRange(end.minusMinutes(windowMinutes), end);
    }

    private record TimeRange(LocalDateTime start, LocalDateTime end) {
    }

}
