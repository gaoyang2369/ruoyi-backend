package org.ruoyi.fault.diagnosis;

import org.junit.jupiter.api.Test;
import org.ruoyi.fault.knowledge.FaultKnowledgePort;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证编排器只经由 FaultKnowledgePort 访问故障知识。 */
class FaultDiagnosisOrchestratorTest {

    @Test
    void delegatesKnowledgeAccessToPort() {
        FaultKnowledgePort port = mock(FaultKnowledgePort.class);
        FaultDiagnosisOrchestrator orchestrator = new FaultDiagnosisOrchestrator(port);
        FaultKnowledgeQuery query = new FaultKnowledgeQuery("F30005", "G120", List.of(9L));
        FaultKnowledgeResult expected = FaultKnowledgeResult.unmatched(query, List.of());
        when(port.query(query)).thenReturn(expected);

        FaultKnowledgeResult actual = orchestrator.queryFaultKnowledge(query);

        assertSame(expected, actual);
        verify(port).query(query);
    }
}
