package org.ruoyi.controller.fault;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ruoyi.domain.bo.fault.FaultKnowledgeLookupBo;
import org.ruoyi.fault.diagnosis.FaultDiagnosisOrchestrator;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.service.fault.AgentFaultKnowledgeQueryFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 故障手册知识查询接口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/fault/knowledge")
public class FaultKnowledgeController {

    private final AgentFaultKnowledgeQueryFactory queryFactory;
    private final FaultDiagnosisOrchestrator faultDiagnosisOrchestrator;

    @PostMapping("/query")
    public Object query(@Valid @RequestBody FaultKnowledgeLookupBo request) {
        FaultKnowledgeQuery query = queryFactory.fromAgent(
            request.getAgentId(), request.getDeviceModel(), request.getFaultCode());
        FaultKnowledgeResult result = faultDiagnosisOrchestrator.queryFaultKnowledge(query);
        return result.matched() ? result : Map.of("matched", false);
    }
}
