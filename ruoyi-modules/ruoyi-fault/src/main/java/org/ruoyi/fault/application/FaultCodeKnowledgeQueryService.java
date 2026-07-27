package org.ruoyi.fault.application;

import lombok.RequiredArgsConstructor;
import org.ruoyi.fault.knowledge.FaultKnowledgePort;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.springframework.stereotype.Service;

import java.util.List;

/** 对 Agent 已绑定知识库的精确故障码查询，不承担通用检索。 */
@Service
@RequiredArgsConstructor
public class FaultCodeKnowledgeQueryService {
    private final FaultKnowledgePort faultKnowledgePort;

    public FaultKnowledgeResult query(String faultCode, List<Long> knowledgeBaseIds) {
        FaultKnowledgeQuery query = new FaultKnowledgeQuery(faultCode, knowledgeBaseIds);
        try {
            FaultKnowledgeResult result = faultKnowledgePort.query(query);
            return result == null ? FaultKnowledgeResult.failed(query) : result;
        } catch (RuntimeException e) {
            return FaultKnowledgeResult.failed(query);
        }
    }
}
