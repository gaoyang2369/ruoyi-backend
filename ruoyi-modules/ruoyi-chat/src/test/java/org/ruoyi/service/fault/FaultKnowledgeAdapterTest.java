package org.ruoyi.service.fault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.domain.vo.knowledge.KnowledgeFragmentVo;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证字面故障码检索到领域证据和三态结果的映射。 */
@ExtendWith(MockitoExtension.class)
class FaultKnowledgeAdapterTest {

    @Mock
    private KnowledgeFragmentMapper knowledgeFragmentMapper;
    @InjectMocks
    private FaultKnowledgeAdapter adapter;

    @Test
    void returnsMatchedForKnownFaultCodeEvenWhenSourceDocumentIsMissing() {
        KnowledgeFragmentVo fragment = fragment(7L, "manual-g120", "F30005：I²t 变频器过载。");
        when(knowledgeFragmentMapper.searchByLiteralFaultCode(9L, "F30005", 20)).thenReturn(List.of(fragment));

        FaultKnowledgeResult result = adapter.query(new FaultKnowledgeQuery("F30005", List.of(9L)));

        assertEquals(FaultKnowledgeResult.Status.MATCHED, result.status());
        assertTrue(result.matched());
        assertEquals("manual-g120", result.evidence().get(0).documentId());
        assertEquals("7", result.evidence().get(0).fragmentId());
        assertNull(result.evidence().get(0).sourceDocument());
        ArgumentCaptor<String> faultCodeCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeFragmentMapper).searchByLiteralFaultCode(eq(9L), faultCodeCaptor.capture(), eq(20));
        assertEquals("F30005", faultCodeCaptor.getValue());
    }

    @Test
    void returnsNotFoundForUnknownFaultCodeAfterSuccessfulQuery() {
        when(knowledgeFragmentMapper.searchByLiteralFaultCode(9L, "F99999", 20)).thenReturn(List.of());

        FaultKnowledgeResult result = adapter.query(new FaultKnowledgeQuery("F99999", List.of(9L)));

        assertEquals(FaultKnowledgeResult.Status.NOT_FOUND, result.status());
        assertFalse(result.matched());
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    void returnsFailedWhenEveryKnowledgeBaseQueryFails() {
        when(knowledgeFragmentMapper.searchByLiteralFaultCode(any(), eq("F99999"), any()))
            .thenThrow(new IllegalStateException("database unavailable"));

        FaultKnowledgeResult result = adapter.query(new FaultKnowledgeQuery("F99999", List.of(9L, 10L)));

        assertEquals(FaultKnowledgeResult.Status.FAILED, result.status());
        assertEquals("FAULT_KNOWLEDGE_RETRIEVAL_FAILED", result.errorCode());
        assertEquals("故障知识查询暂不可用，请稍后重试", result.errorMessage());
    }

    @Test
    void keepsSearchingWhenOneKnowledgeBaseFailsAndAnotherMatches() {
        KnowledgeFragmentVo fragment = fragment(8L, "manual-g120", "F30005：I²t 变频器过载。");
        fragment.setSourceDocument("SINAMICS G120 操作手册");
        when(knowledgeFragmentMapper.searchByLiteralFaultCode(9L, "F30005", 20))
            .thenThrow(new IllegalStateException("database unavailable"));
        when(knowledgeFragmentMapper.searchByLiteralFaultCode(10L, "F30005", 20)).thenReturn(List.of(fragment));

        FaultKnowledgeResult result = adapter.query(new FaultKnowledgeQuery("F30005", List.of(9L, 10L)));

        assertEquals(FaultKnowledgeResult.Status.MATCHED, result.status());
        assertEquals("SINAMICS G120 操作手册", result.evidence().get(0).sourceDocument());
    }

    @Test
    void keepsFollowingFragmentContextButStopsBeforeNextFaultCode() {
        KnowledgeFragmentVo fragment = fragment(8L, "manual-s120", """
            F07560 上一个故障的结尾。
            F07561 驱动编码器：多圈线数不是二的幂次方。
            原因：p0421 中的多圈分辨率必须是二的幂次方。
            处理：检查参数设定，必要时升级编码器模块固件。
            F07562 下一个故障。
            """);
        when(knowledgeFragmentMapper.searchByLiteralFaultCode(9L, "F07561", 20)).thenReturn(List.of(fragment));

        FaultKnowledgeResult result = adapter.query(new FaultKnowledgeQuery("F07561", List.of(9L)));

        String content = result.evidence().get(0).content();
        assertTrue(content.startsWith("F07561"));
        assertTrue(content.contains("原因：p0421"));
        assertTrue(content.contains("处理：检查参数设定"));
        assertFalse(content.contains("F07560"));
        assertFalse(content.contains("F07562"));
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
