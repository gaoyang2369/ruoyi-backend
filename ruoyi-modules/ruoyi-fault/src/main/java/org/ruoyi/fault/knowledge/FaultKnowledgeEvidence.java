package org.ruoyi.fault.knowledge;

/**
 * 可作为诊断结论依据的故障手册片段。
 */
public record FaultKnowledgeEvidence(
    Long knowledgeBaseId,
    String documentId,
    String sourceDocument,
    String fragmentId,
    Integer fragmentIndex,
    String contentHash,
    String content
) {
}
