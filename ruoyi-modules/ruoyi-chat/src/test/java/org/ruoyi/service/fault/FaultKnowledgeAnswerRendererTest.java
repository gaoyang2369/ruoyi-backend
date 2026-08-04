package org.ruoyi.service.fault;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.service.fault.model.FaultKnowledgeAnswerDraft;
import org.ruoyi.service.fault.model.FaultKnowledgeFacts;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class FaultKnowledgeAnswerRendererTest {

    @Test
    void rendersSectionedFallbackWithAllFaultValueBranches() {
        FaultKnowledgeFacts facts = facts();

        String markdown = FaultKnowledgeAnswerRenderer.renderFallback(List.of(facts));

        assertTrue(markdown.contains("### F13000：授权不够"));
        assertTrue(markdown.contains("**可能原因**"));
        assertTrue(markdown.contains("**先检查什么**"));
        assertTrue(markdown.contains("请先读取 `r0949`"));
        assertTrue(markdown.contains("- `0`：现有授权不够"));
        assertTrue(markdown.contains("- `1`：存储卡中没有授权数据"));
        assertTrue(markdown.contains("输入许可密钥"));
    }

    @Test
    void validatesCompleteDraftAndRejectsMissingBranchOrInventedParameter() {
        FaultKnowledgeFacts facts = facts();
        FaultKnowledgeAnswerDraft complete = draft("读取 r0949 后按故障值处理。",
            List.of(branch("0", "现有授权不够", "激活 p9920。"),
                branch("1", "存储卡中没有授权数据", "输入许可密钥。")));
        FaultKnowledgeAnswerDraft missingBranch = draft("读取 r0949 后按故障值处理。",
            List.of(branch("0", "现有授权不够", "激活 p9920。")));
        FaultKnowledgeAnswerDraft inventedParameter = draft("读取 p9999 后处理。",
            List.of(branch("0", "现有授权不够", "激活 p9920。"),
                branch("1", "存储卡中没有授权数据", "输入许可密钥。")));

        assertTrue(FaultKnowledgeAnswerRenderer.valid(complete, List.of(facts), List.of("F13000")));
        assertFalse(FaultKnowledgeAnswerRenderer.valid(missingBranch, List.of(facts), List.of("F13000")));
        assertFalse(FaultKnowledgeAnswerRenderer.valid(inventedParameter, List.of(facts), List.of("F13000")));
    }

    private static FaultKnowledgeFacts facts() {
        return new FaultKnowledgeFacts("F13000", "授权不够", "授权不足。", null, null, "r0949",
            List.of(
                new FaultKnowledgeFacts.FaultValueBranch("0", "现有授权不够",
                    List.of("激活 p9920。")),
                new FaultKnowledgeFacts.FaultValueBranch("1", "存储卡中没有授权数据",
                    List.of("输入许可密钥。"))),
            null);
    }

    private static FaultKnowledgeAnswerDraft draft(
        String summary, List<FaultKnowledgeAnswerDraft.ActionBranch> branches) {
        return new FaultKnowledgeAnswerDraft(List.of(new FaultKnowledgeAnswerDraft.FaultAnswer(
            "F13000", summary, List.of("授权不足。"), "读取 r0949。",
            branches, List.of(), List.of("r0949", "p9920"), List.of())));
    }

    private static FaultKnowledgeAnswerDraft.ActionBranch branch(String value, String meaning, String action) {
        return new FaultKnowledgeAnswerDraft.ActionBranch(value, meaning, List.of(action));
    }
}
