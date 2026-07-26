package org.ruoyi.service.fault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeAttach;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.common.chat.service.chat.IChatModelService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 RAG 检索结果到故障领域证据及证据链的映射。 */
@ExtendWith(MockitoExtension.class)
class RagFaultKnowledgeAdapterTest {

    @Mock
    private KnowledgeRetrievalService knowledgeRetrievalService;
    @Mock
    private IKnowledgeInfoService knowledgeInfoService;
    @Mock
    private IChatModelService chatModelService;
    @Mock
    private KnowledgeAttachMapper knowledgeAttachMapper;
    @InjectMocks
    private RagFaultKnowledgeAdapter adapter;

    @Test
    void mapsExactRagHitAndWritesEvidenceChain() {
        KnowledgeInfoVo knowledgeBase = new KnowledgeInfoVo();
        knowledgeBase.setEmbeddingModel("embedding-model");
        knowledgeBase.setVectorModel("qdrant");
        knowledgeBase.setRetrieveLimit(5);
        ChatModelVo embeddingModel = new ChatModelVo();
        embeddingModel.setApiKey("test-key");
        embeddingModel.setApiHost("https://embedding.example");
        KnowledgeRetrievalVo retrieved = new KnowledgeRetrievalVo();
        retrieved.setId("fragment-7");
        retrieved.setDocId("manual-g120");
        retrieved.setIdx(7);
        retrieved.setScore(0.93D);
        retrieved.setContent("F30005：I²t 变频器过载。");
        KnowledgeAttach attachment = new KnowledgeAttach();
        attachment.setName("SINAMICS G120 操作手册");
        when(knowledgeInfoService.queryById(9L)).thenReturn(knowledgeBase);
        when(chatModelService.selectModelByName("embedding-model")).thenReturn(embeddingModel);
        when(knowledgeRetrievalService.retrieve(any(QueryVectorBo.class))).thenReturn(List.of(retrieved));
        when(knowledgeAttachMapper.selectOne(any())).thenReturn(attachment);

        FaultKnowledgeResult result = adapter.query(new FaultKnowledgeQuery("F30005", "G120", List.of(9L)));

        assertTrue(result.matched());
        assertEquals("F30005", result.faultCode());
        assertEquals("manual-g120", result.evidence().get(0).documentId());
        assertEquals("fragment-7", result.evidence().get(0).fragmentId());
        assertEquals("SINAMICS G120 操作手册", result.evidence().get(0).sourceDocument());
        assertEquals(0.93D, result.evidence().get(0).score());
        assertTrue(result.evidence().get(0).exactCodeMatched());
        assertTrue(result.evidence().get(0).contentHash().startsWith("sha256:"));
        assertEquals(1, result.evidenceChain().retrievals().size());
        ArgumentCaptor<QueryVectorBo> requestCaptor = ArgumentCaptor.forClass(QueryVectorBo.class);
        verify(knowledgeRetrievalService).retrieve(requestCaptor.capture());
        assertEquals("G120 F30005", requestCaptor.getValue().getQuery());
        assertEquals("9", requestCaptor.getValue().getKid());
    }

    @Test
    void preservesSimilarCodeInTraceButDoesNotMakeItEvidence() {
        KnowledgeInfoVo knowledgeBase = new KnowledgeInfoVo();
        knowledgeBase.setEmbeddingModel("embedding-model");
        ChatModelVo embeddingModel = new ChatModelVo();
        KnowledgeRetrievalVo retrieved = new KnowledgeRetrievalVo();
        retrieved.setSourceName("SINAMICS G120 操作手册");
        retrieved.setContent("F99999A：相邻故障码说明。");
        when(knowledgeInfoService.queryById(9L)).thenReturn(knowledgeBase);
        when(chatModelService.selectModelByName("embedding-model")).thenReturn(embeddingModel);
        when(knowledgeRetrievalService.retrieve(any(QueryVectorBo.class))).thenReturn(List.of(retrieved));

        FaultKnowledgeResult result = adapter.query(new FaultKnowledgeQuery("F99999", "G120", List.of(9L)));

        assertFalse(result.matched());
        assertTrue(result.evidence().isEmpty());
        assertFalse(result.evidenceChain().retrievals().get(0).exactCodeMatched());
    }
}
