package org.ruoyi.service.fault;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.domain.enums.agent.AgentScenarioCode;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.service.agent.IAgentService;
import org.springframework.stereotype.Service;

/**
 * 从 Agent 绑定配置构建故障知识查询，防止接口调用方任意扩大知识库检索范围。
 */
@Service
@RequiredArgsConstructor
public class AgentFaultKnowledgeQueryFactory {

    private final IAgentService agentService;

    public FaultKnowledgeQuery fromAgent(Long agentId, String faultCode) {
        if (agentId == null) {
            throw new ServiceException("Agent ID不能为空");
        }
        AgentVo agent = agentService.queryById(agentId);
        if (agent == null) {
            throw new ServiceException("故障诊断Agent不存在: " + agentId);
        }
        if (!"0".equals(agent.getStatus())) {
            throw new ServiceException("故障诊断Agent未启用: " + agentId);
        }
        if (!AgentScenarioCode.FAULT_DIAGNOSIS.name().equals(agent.getScenarioCode())) {
            throw new ServiceException("Agent不是故障诊断场景: " + agentId);
        }
        if (agent.getKnowledgeIds() == null || agent.getKnowledgeIds().isEmpty()) {
            throw new ServiceException("故障诊断Agent未绑定知识库: " + agentId);
        }
        try {
            return new FaultKnowledgeQuery(FaultKnowledgeQuery.normalizeFaultCode(faultCode), agent.getKnowledgeIds());
        } catch (IllegalArgumentException e) {
            throw new ServiceException(e.getMessage());
        }
    }
}
