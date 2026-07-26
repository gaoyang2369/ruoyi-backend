package org.ruoyi.service.fault;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.ruoyi.domain.entity.knowledge.KnowledgeAttach;
import org.ruoyi.domain.vo.knowledge.KnowledgeFragmentVo;
import org.ruoyi.fault.knowledge.FaultCodeExactMatcher;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.knowledge.FaultKnowledgePort;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.fault.knowledge.FaultKnowledgeRetrievalTrace;
import org.ruoyi.fault.knowledge.FaultKnowledgeRetrievalStatus;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 将通用 RAG 检索结果映射为故障诊断领域端口。
 * <p>
 * 故障码查询优先采用已入库片段的参数化字面检索，避免把向量召回作为精确编码查询的必要条件。
 * 每个候选片段仍须通过领域层的精确 token 校验后，才能成为正式证据。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RagFaultKnowledgeAdapter implements FaultKnowledgePort {

    private static final int LITERAL_SEARCH_LIMIT = 20;

    private final KnowledgeFragmentMapper knowledgeFragmentMapper;
    private final KnowledgeAttachMapper knowledgeAttachMapper;

    @Override
    public FaultKnowledgeResult query(FaultKnowledgeQuery query) {
        List<FaultKnowledgeEvidence> evidence = new ArrayList<>();
        List<FaultKnowledgeRetrievalTrace> traces = new ArrayList<>();
        int successfulKnowledgeBaseCount = 0;

        for (Long knowledgeBaseId : query.knowledgeBaseIds()) {
            try {
                List<KnowledgeFragmentVo> candidates = knowledgeFragmentMapper.searchByLiteralFaultCode(
                    knowledgeBaseId, query.faultCode(), LITERAL_SEARCH_LIMIT);
                successfulKnowledgeBaseCount++;
                mapCandidates(query, knowledgeBaseId, candidates, evidence, traces);
            } catch (RuntimeException e) {
                log.error("故障知识库字面检索失败: knowledgeBaseId={}, faultCode={}",
                    knowledgeBaseId, query.faultCode(), e);
                traces.add(failedTrace(knowledgeBaseId, query.retrievalQuery(), null, null));
            }
        }

        if (!evidence.isEmpty()) {
            return FaultKnowledgeResult.matched(query, evidence, traces);
        }
        return successfulKnowledgeBaseCount > 0
            ? FaultKnowledgeResult.notFound(query, traces)
            : FaultKnowledgeResult.failed(query, traces);
    }

    private void mapCandidates(FaultKnowledgeQuery query, Long knowledgeBaseId, List<KnowledgeFragmentVo> candidates,
                               List<FaultKnowledgeEvidence> evidence,
                               List<FaultKnowledgeRetrievalTrace> traces) {
        if (candidates == null || candidates.isEmpty()) {
            traces.add(successTrace(knowledgeBaseId, query.retrievalQuery(), null, null, null, null, false));
            return;
        }
        for (KnowledgeFragmentVo candidate : candidates) {
            try {
                boolean exactCodeMatched = FaultCodeExactMatcher.matches(candidate.getContent(), query.faultCode());
                String sourceDocument = resolveSourceDocument(knowledgeBaseId, candidate.getDocId());
                String contentHash = sha256(candidate.getContent());
                traces.add(successTrace(knowledgeBaseId, query.retrievalQuery(), candidate.getDocId(),
                    sourceDocument, candidate.getId(), candidate.getIdx(), exactCodeMatched, contentHash));
                if (exactCodeMatched && hasReliableSource(sourceDocument)) {
                    evidence.add(new FaultKnowledgeEvidence(knowledgeBaseId, candidate.getDocId(), sourceDocument,
                        String.valueOf(candidate.getId()), candidate.getIdx(), contentHash, candidate.getContent()));
                }
            } catch (RuntimeException e) {
                log.error("故障知识候选片段映射失败: knowledgeBaseId={}, fragmentId={}",
                    knowledgeBaseId, candidate.getId(), e);
                traces.add(failedTrace(knowledgeBaseId, query.retrievalQuery(), candidate.getDocId(),
                    String.valueOf(candidate.getId())));
            }
        }
    }

    private String resolveSourceDocument(Long knowledgeBaseId, String docId) {
        if (!StringUtils.hasText(docId)) {
            return null;
        }
        KnowledgeAttach attachment = knowledgeAttachMapper.selectOne(Wrappers.<KnowledgeAttach>lambdaQuery()
            .eq(KnowledgeAttach::getKnowledgeId, knowledgeBaseId)
            .eq(KnowledgeAttach::getDocId, docId));
        return attachment == null ? null : attachment.getName();
    }

    private boolean hasReliableSource(String sourceDocument) {
        return StringUtils.hasText(sourceDocument) && !"未知来源".equals(sourceDocument.trim());
    }

    private FaultKnowledgeRetrievalTrace successTrace(Long knowledgeBaseId, String retrievalQuery, String documentId,
                                                       String sourceDocument, Long fragmentId, Integer fragmentIndex,
                                                       boolean exactCodeMatched) {
        return successTrace(knowledgeBaseId, retrievalQuery, documentId, sourceDocument, fragmentId, fragmentIndex,
            exactCodeMatched, null);
    }

    private FaultKnowledgeRetrievalTrace successTrace(Long knowledgeBaseId, String retrievalQuery, String documentId,
                                                       String sourceDocument, Long fragmentId, Integer fragmentIndex,
                                                       boolean exactCodeMatched, String contentHash) {
        return new FaultKnowledgeRetrievalTrace(knowledgeBaseId, retrievalQuery, documentId, sourceDocument,
            fragmentId == null ? null : String.valueOf(fragmentId), fragmentIndex, contentHash,
            exactCodeMatched, FaultKnowledgeRetrievalStatus.SUCCESS, null);
    }

    private FaultKnowledgeRetrievalTrace failedTrace(Long knowledgeBaseId, String retrievalQuery, String documentId,
                                                      String fragmentId) {
        return new FaultKnowledgeRetrievalTrace(knowledgeBaseId, retrievalQuery, documentId, null,
            fragmentId, null, null, false, FaultKnowledgeRetrievalStatus.FAILED,
            "FAULT_KNOWLEDGE_RETRIEVAL_FAILED");
    }

    private String sha256(String content) {
        if (content == null) {
            return null;
        }
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少SHA-256摘要算法", e);
        }
    }
}
