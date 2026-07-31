package org.ruoyi.service.fault;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.domain.vo.knowledge.KnowledgeFragmentVo;
import org.ruoyi.fault.knowledge.FaultCodeExactMatcher;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.knowledge.FaultKnowledgePort;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于知识片段字面查询实现故障知识端口。
 * <p>
 * 该实现不使用 {@code KnowledgeRetrievalService} 或向量检索；候选片段经故障码精确匹配后直接成为证据。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FaultKnowledgeAdapter implements FaultKnowledgePort {

    private static final int LITERAL_SEARCH_LIMIT = 20;
    private static final Pattern FAULT_SECTION_BOUNDARY =
        Pattern.compile("(?i)(?<![A-Z0-9_-])[AF]\\d{3,}(?![A-Z0-9_-])");

    private final KnowledgeFragmentMapper knowledgeFragmentMapper;

    @Override
    public FaultKnowledgeResult query(FaultKnowledgeQuery query) {
        List<FaultKnowledgeEvidence> evidence = new ArrayList<>();
        int successfulKnowledgeBaseCount = 0;

        for (Long knowledgeBaseId : query.knowledgeBaseIds()) {
            try {
                List<KnowledgeFragmentVo> candidates = knowledgeFragmentMapper.searchByLiteralFaultCode(
                    knowledgeBaseId, query.faultCode(), LITERAL_SEARCH_LIMIT);
                successfulKnowledgeBaseCount++;
                mapExactMatches(query, knowledgeBaseId, candidates, evidence);
            } catch (RuntimeException e) {
                log.error("故障知识库字面检索失败: knowledgeBaseId={}, faultCode={}",
                    knowledgeBaseId, query.faultCode(), e);
            }
        }

        if (!evidence.isEmpty()) {
            return FaultKnowledgeResult.matched(query, evidence);
        }
        return successfulKnowledgeBaseCount > 0
            ? FaultKnowledgeResult.notFound(query)
            : FaultKnowledgeResult.failed(query);
    }

    private void mapExactMatches(FaultKnowledgeQuery query, Long knowledgeBaseId, List<KnowledgeFragmentVo> candidates,
                                 List<FaultKnowledgeEvidence> evidence) {
        if (candidates == null) {
            return;
        }
        for (KnowledgeFragmentVo candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (FaultCodeExactMatcher.matches(candidate.getContent(), query.faultCode())) {
                evidence.add(new FaultKnowledgeEvidence(knowledgeBaseId, candidate.getDocId(),
                    candidate.getSourceDocument(), String.valueOf(candidate.getId()), candidate.getIdx(),
                    extractFaultSection(candidate.getContent(), query.faultCode())));
            }
        }
    }

    /**
     * 从“命中片段 + 下一片段”的有界上下文中，仅保留当前故障码条目。
     */
    private static String extractFaultSection(String content, String faultCode) {
        if (content == null || content.isBlank()) return content;
        Pattern currentCode = Pattern.compile("(?i)(?<![A-Z0-9_-])" + Pattern.quote(faultCode)
            + "(?![A-Z0-9_-])");
        Matcher current = currentCode.matcher(content);
        if (!current.find()) return content;
        String fromCurrent = content.substring(current.start()).trim();
        Matcher boundary = FAULT_SECTION_BOUNDARY.matcher(fromCurrent);
        if (!boundary.find()) return fromCurrent;
        if (boundary.find()) return fromCurrent.substring(0, boundary.start()).trim();
        return fromCurrent;
    }
}
