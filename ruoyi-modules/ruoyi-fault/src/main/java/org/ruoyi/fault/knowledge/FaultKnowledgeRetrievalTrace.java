package org.ruoyi.fault.knowledge;

/**
 * 一条知识库候选片段的内存检索轨迹；即使未精确命中也会被保留以便审计。
 */
public record FaultKnowledgeRetrievalTrace(
    Long knowledgeBaseId,
    String retrievalQuery,
    String documentId,
    String sourceDocument,
    String fragmentId,
    Integer fragmentIndex,
    String contentHash,
    boolean exactCodeMatched,
    FaultKnowledgeRetrievalStatus status,
    String errorCode
) {
}
