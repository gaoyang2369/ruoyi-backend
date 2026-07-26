package org.ruoyi.service.fault;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeAttach;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;
import org.ruoyi.fault.knowledge.FaultCodeExactMatcher;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidenceChain;
import org.ruoyi.fault.knowledge.FaultKnowledgePort;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.fault.knowledge.FaultKnowledgeRetrievalTrace;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
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
 * 该适配器直接使用 {@link KnowledgeRetrievalService#retrieve(QueryVectorBo)}，以保留候选片段、
 * 文档标识、相似度得分和来源信息；不会调用仅返回文本的 retrieveTexts。
 */
@Service
@RequiredArgsConstructor
public class RagFaultKnowledgeAdapter implements FaultKnowledgePort {

    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final IKnowledgeInfoService knowledgeInfoService;
    private final IChatModelService chatModelService;
    private final KnowledgeAttachMapper knowledgeAttachMapper;

    @Override
    public FaultKnowledgeResult query(FaultKnowledgeQuery query) {
        List<FaultKnowledgeEvidence> evidence = new ArrayList<>();
        List<FaultKnowledgeRetrievalTrace> traces = new ArrayList<>();

        for (Long knowledgeBaseId : query.knowledgeBaseIds()) {
            try {
                List<KnowledgeRetrievalVo> retrieved = knowledgeRetrievalService.retrieve(
                    buildRetrievalRequest(knowledgeBaseId, query.retrievalQuery()));
                if (retrieved == null || retrieved.isEmpty()) {
                    traces.add(emptyTrace(knowledgeBaseId, query.retrievalQuery(), null));
                    continue;
                }
                for (KnowledgeRetrievalVo candidate : retrieved) {
                    boolean exactCodeMatched = FaultCodeExactMatcher.matches(candidate.getContent(), query.faultCode());
                    String sourceDocument = resolveSourceDocument(knowledgeBaseId, candidate);
                    String contentHash = sha256(candidate.getContent());
                    traces.add(new FaultKnowledgeRetrievalTrace(
                        knowledgeBaseId, query.retrievalQuery(), candidate.getDocId(), sourceDocument,
                        candidate.getId(), candidate.getIdx(), candidate.getScore(), contentHash,
                        exactCodeMatched, null));
                    if (exactCodeMatched && hasReliableSource(sourceDocument)) {
                        evidence.add(new FaultKnowledgeEvidence(
                            knowledgeBaseId, candidate.getDocId(), sourceDocument, candidate.getId(),
                            candidate.getIdx(), candidate.getScore(), contentHash, true, candidate.getContent()));
                    }
                }
            } catch (RuntimeException e) {
                traces.add(emptyTrace(knowledgeBaseId, query.retrievalQuery(), e.getMessage()));
            }
        }

        return evidence.isEmpty()
            ? FaultKnowledgeResult.unmatched(query, traces)
            : new FaultKnowledgeResult(true, query.faultCode(), evidence,
                new FaultKnowledgeEvidenceChain(query, traces));
    }

    private QueryVectorBo buildRetrievalRequest(Long knowledgeBaseId, String retrievalQuery) {
        KnowledgeInfoVo knowledgeBase = knowledgeInfoService.queryById(knowledgeBaseId);
        if (knowledgeBase == null) {
            throw new IllegalArgumentException("故障知识库不存在: " + knowledgeBaseId);
        }
        ChatModelVo embeddingModel = chatModelService.selectModelByName(knowledgeBase.getEmbeddingModel());
        if (embeddingModel == null) {
            throw new IllegalArgumentException("故障知识库未配置可用向量模型: " + knowledgeBaseId);
        }

        QueryVectorBo request = new QueryVectorBo();
        request.setQuery(retrievalQuery);
        request.setKid(String.valueOf(knowledgeBaseId));
        request.setApiKey(embeddingModel.getApiKey());
        request.setBaseUrl(embeddingModel.getApiHost());
        request.setEmbeddingModelName(knowledgeBase.getEmbeddingModel());
        request.setVectorModelName(knowledgeBase.getVectorModel());
        request.setMaxResults(knowledgeBase.getRetrieveLimit());
        request.setSimilarityThreshold(knowledgeBase.getSimilarityThreshold());
        request.setEnableHybrid(Integer.valueOf(1).equals(knowledgeBase.getEnableHybrid()));
        request.setHybridAlpha(knowledgeBase.getHybridAlpha());
        request.setEnableRerank(Integer.valueOf(1).equals(knowledgeBase.getEnableRerank()));
        request.setRerankModelName(knowledgeBase.getRerankModel());
        request.setRerankTopN(knowledgeBase.getRerankTopN());
        request.setRerankScoreThreshold(knowledgeBase.getRerankScoreThreshold());
        return request;
    }

    private String resolveSourceDocument(Long knowledgeBaseId, KnowledgeRetrievalVo candidate) {
        if (hasReliableSource(candidate.getSourceName())) {
            return candidate.getSourceName().trim();
        }
        if (!StringUtils.hasText(candidate.getDocId())) {
            return null;
        }
        KnowledgeAttach attachment = knowledgeAttachMapper.selectOne(Wrappers.<KnowledgeAttach>lambdaQuery()
            .eq(KnowledgeAttach::getKnowledgeId, knowledgeBaseId)
            .eq(KnowledgeAttach::getDocId, candidate.getDocId()));
        return attachment == null ? null : attachment.getName();
    }

    private boolean hasReliableSource(String sourceDocument) {
        return StringUtils.hasText(sourceDocument) && !"未知来源".equals(sourceDocument.trim());
    }

    private FaultKnowledgeRetrievalTrace emptyTrace(Long knowledgeBaseId, String retrievalQuery, String error) {
        return new FaultKnowledgeRetrievalTrace(knowledgeBaseId, retrievalQuery, null, null,
            null, null, null, null, false, error);
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
