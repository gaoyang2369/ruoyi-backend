package org.ruoyi.service.fault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.service.agent.IAgentService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** 验证故障知识库范围只能从 Agent 绑定配置取得。 */
@ExtendWith(MockitoExtension.class)
class AgentFaultKnowledgeQueryFactoryTest {

    @Mock
    private IAgentService agentService;
    @InjectMocks
    private AgentFaultKnowledgeQueryFactory queryFactory;

    @Test
    void buildsQueryFromAgentBoundKnowledgeBases() {
        AgentVo agent = new AgentVo();
        agent.setKnowledgeIds(List.of(9L, 10L));
        when(agentService.queryById(7L)).thenReturn(agent);

        FaultKnowledgeQuery query = queryFactory.fromAgent(7L, "G120", "f30005");

        assertEquals("F30005", query.faultCode());
        assertEquals("G120", query.deviceModel());
        assertEquals(List.of(9L, 10L), query.knowledgeBaseIds());
    }
}
