package org.ruoyi.service.fault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.domain.entity.knowledge.KnowledgeAttach;
import org.ruoyi.domain.vo.knowledge.KnowledgeFragmentVo;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.fault.knowledge.FaultKnowledgeRetrievalStatus;
import org.ruoyi.fault.knowledge.FaultKnowledgeStatus;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证字面故障码检索到领域证据和三态结果的映射。 */
@ExtendWith(MockitoExtension.class)
class RagFaultKnowledgeAdapterTest {

    @Mock
    private KnowledgeFragmentMapper knowledgeFragmentMapper;
    @Mock
    private KnowledgeAttachMapper knowledgeAttachMapper;
    @InjectMocks
    private RagFaultKnowledgeAdapter adapter;

    @Test
    void returnsMatchedForKnownFaultCode() {
        KnowledgeFragmentVo fragment = fragment(7L, "manual-g120", "F30005：I²t 变频器过载。");
        KnowledgeAttach attachment = new KnowledgeAttach();
        attachment.setName("SINAMICS G120 操作手册");
        when(knowledgeFragmentMapper.searchByLiteralFaultCode(9L, "F30005", 20)).thenReturn(List.of(fragment));
        when(knowledgeAttachMapper.selectOne(any())).thenReturn(attachment);

        FaultKnowledgeResult result = adapter.query(new FaultKnowledgeQuery("F30005", "G120", List.of(9L)));

        assertEquals(FaultKnowledgeStatus.MATCHED, result.status());
        assertTrue(result.matched());
        assertEquals("manual-g120", result.evidence().get(0).documentId());
        assertEquals("7", result.evidence().get(0).fragmentId());
        assertEquals("SINAMICS G120 操作手册", result.evidence().get(0).sourceDocument());
        assertTrue(result.evidence().get(0).contentHash().startsWith("sha256:"));
        ArgumentCaptor<String> faultCodeCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeFragmentMapper).searchByLiteralFaultCode(eq(9L), faultCodeCaptor.capture(), eq(20));
        assertEquals("F30005", faultCodeCaptor.getValue());
    }

    @Test
    void returnsNotFoundForUnknownFaultCodeAfterSuccessfulQuery() {
        when(knowledgeFragmentMapper.searchByLiteralFaultCode(9L, "F99999", 20)).thenReturn(List.of());

        FaultKnowledgeResult result = adapter.query(new FaultKnowledgeQuery("F99999", "G120", List.of(9L)));

        assertEquals(FaultKnowledgeStatus.NOT_FOUND, result.status());
        assertFalse(result.matched());
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    void returnsFailedWhenEveryKnowledgeBaseQueryFails() {
        when(knowledgeFragmentMapper.searchByLiteralFaultCode(any(), eq("F99999"), any()))
            .thenThrow(new IllegalStateException("database unavailable"));

        FaultKnowledgeResult result = adapter.query(new FaultKnowledgeQuery("F99999", "G120", List.of(9L, 10L)));

        assertEquals(FaultKnowledgeStatus.FAILED, result.status());
        assertEquals("FAULT_KNOWLEDGE_RETRIEVAL_FAILED", result.errorCode());
        assertEquals("故障知识查询暂不可用，请稍后重试", result.errorMessage());
        assertTrue(result.evidenceChain().retrievals().stream()
            .allMatch(trace -> trace.status() == FaultKnowledgeRetrievalStatus.FAILED));
    }

    @Test
    void keepsSearchingWhenOneKnowledgeBaseFailsAndAnotherMatches() {
        KnowledgeFragmentVo fragment = fragment(8L, "manual-g120", "F30005：I²t 变频器过载。");
        KnowledgeAttach attachment = new KnowledgeAttach();
        attachment.setName("SINAMICS G120 操作手册");
        when(knowledgeFragmentMapper.searchByLiteralFaultCode(9L, "F30005", 20))
            .thenThrow(new IllegalStateException("database unavailable"));
        when(knowledgeFragmentMapper.searchByLiteralFaultCode(10L, "F30005", 20)).thenReturn(List.of(fragment));
        when(knowledgeAttachMapper.selectOne(any())).thenReturn(attachment);

        FaultKnowledgeResult result = adapter.query(new FaultKnowledgeQuery("F30005", "G120", List.of(9L, 10L)));

        assertEquals(FaultKnowledgeStatus.MATCHED, result.status());
        assertEquals(2, result.evidenceChain().retrievals().size());
        assertEquals(FaultKnowledgeRetrievalStatus.FAILED, result.evidenceChain().retrievals().get(0).status());
    }

    private KnowledgeFragmentVo fragment(Long id, String docId, String content) {
        KnowledgeFragmentVo fragment = new KnowledgeFragmentVo();
        fragment.setId(id);
        fragment.setDocId(docId);
        fragment.setIdx(7);
        fragment.setContent(content);
        return fragment;
    }
}
