package org.ruoyi.fault.knowledge;

/**
 * 一条 RAG 候选片段的证据链记录；即使未精确命中也会被保留以便审计。
 */
public record FaultKnowledgeRetrievalTrace(
    Long knowledgeBaseId,
    String retrievalQuery,
    String documentId,
    String sourceDocument,
    String fragmentId,
    Integer fragmentIndex,
    Double score,
    String contentHash,
    boolean exactCodeMatched,
    String retrievalError
) {
}
