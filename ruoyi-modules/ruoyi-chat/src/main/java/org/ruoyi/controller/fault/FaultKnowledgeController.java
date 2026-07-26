package org.ruoyi.controller.fault;

import jakarta.validation.Valid;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.domain.bo.fault.FaultKnowledgeLookupBo;
import org.ruoyi.fault.knowledge.FaultKnowledgePort;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.service.fault.AgentFaultKnowledgeQueryFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 故障手册知识查询接口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/fault/knowledge")
public class FaultKnowledgeController {

    private final AgentFaultKnowledgeQueryFactory queryFactory;
    private final FaultKnowledgePort faultKnowledgePort;

    @SaCheckPermission("fault:knowledge:query")
    @PostMapping("/query")
    public FaultKnowledgeResult query(@Valid @RequestBody FaultKnowledgeLookupBo request) {
        FaultKnowledgeQuery query = queryFactory.fromAgent(request.getAgentId(), request.getFaultCode());
        return faultKnowledgePort.query(query);
    }
}
