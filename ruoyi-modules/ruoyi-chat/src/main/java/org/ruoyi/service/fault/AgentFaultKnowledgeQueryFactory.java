package org.ruoyi.service.fault;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
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

    public FaultKnowledgeQuery fromAgent(Long agentId, String deviceModel, String faultCode) {
        if (agentId == null) {
            throw new ServiceException("Agent ID不能为空");
        }
        AgentVo agent = agentService.queryById(agentId);
        if (agent == null) {
            throw new ServiceException("故障诊断Agent不存在: " + agentId);
        }
        return new FaultKnowledgeQuery(faultCode, deviceModel, agent.getKnowledgeIds());
    }
}
