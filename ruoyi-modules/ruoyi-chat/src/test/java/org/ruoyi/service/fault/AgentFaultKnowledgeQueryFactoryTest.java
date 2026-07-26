package org.ruoyi.service.fault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.domain.enums.agent.AgentScenarioCode;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.service.agent.IAgentService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/** 验证故障知识库范围与使用条件只能从 Agent 绑定配置取得。 */
@ExtendWith(MockitoExtension.class)
class AgentFaultKnowledgeQueryFactoryTest {

    @Mock
    private IAgentService agentService;
    @InjectMocks
    private AgentFaultKnowledgeQueryFactory queryFactory;

    @Test
    void buildsNormalizedQueryFromEnabledFaultDiagnosisAgent() {
        AgentVo agent = enabledFaultDiagnosisAgent();
        agent.setKnowledgeIds(List.of(9L, 10L));
        when(agentService.queryById(7L)).thenReturn(agent);

        FaultKnowledgeQuery query = queryFactory.fromAgent(7L, " f30005 ");

        assertEquals("F30005", query.faultCode());
        assertEquals(List.of(9L, 10L), query.knowledgeBaseIds());
    }

    @Test
    void rejectsDisabledAgent() {
        AgentVo agent = enabledFaultDiagnosisAgent();
        agent.setStatus("1");
        when(agentService.queryById(7L)).thenReturn(agent);

        assertThrows(ServiceException.class, () -> queryFactory.fromAgent(7L, "F30005"));
    }

    @Test
    void rejectsMissingAgent() {
        when(agentService.queryById(7L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> queryFactory.fromAgent(7L, "F30005"));
    }

    @Test
    void rejectsNonFaultDiagnosisAgent() {
        AgentVo agent = enabledFaultDiagnosisAgent();
        agent.setScenarioCode(AgentScenarioCode.GENERAL_CHAT.name());
        when(agentService.queryById(7L)).thenReturn(agent);

        assertThrows(ServiceException.class, () -> queryFactory.fromAgent(7L, "F30005"));
    }

    @Test
    void rejectsAgentWithoutKnowledgeBases() {
        AgentVo agent = enabledFaultDiagnosisAgent();
        agent.setKnowledgeIds(List.of());
        when(agentService.queryById(7L)).thenReturn(agent);

        assertThrows(ServiceException.class, () -> queryFactory.fromAgent(7L, "F30005"));
    }

    @Test
    void rejectsInvalidFaultCode() {
        when(agentService.queryById(7L)).thenReturn(enabledFaultDiagnosisAgent());

        assertThrows(ServiceException.class, () -> queryFactory.fromAgent(7L, "F30005!"));
    }

    private AgentVo enabledFaultDiagnosisAgent() {
        AgentVo agent = new AgentVo();
        agent.setStatus("0");
        agent.setScenarioCode(AgentScenarioCode.FAULT_DIAGNOSIS.name());
        agent.setKnowledgeIds(List.of(9L));
        return agent;
    }
}
