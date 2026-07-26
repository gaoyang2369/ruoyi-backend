package org.ruoyi.fault.diagnosis;

import lombok.RequiredArgsConstructor;
import org.ruoyi.fault.knowledge.FaultKnowledgePort;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.springframework.stereotype.Service;

/**
 * 故障诊断编排入口。知识获取只依赖领域端口，不感知 RAG、向量库或聊天模块。
 */
@Service
@RequiredArgsConstructor
public class FaultDiagnosisOrchestrator {

    private final FaultKnowledgePort faultKnowledgePort;

    public FaultKnowledgeResult queryFaultKnowledge(FaultKnowledgeQuery query) {
        return faultKnowledgePort.query(query);
    }
}
