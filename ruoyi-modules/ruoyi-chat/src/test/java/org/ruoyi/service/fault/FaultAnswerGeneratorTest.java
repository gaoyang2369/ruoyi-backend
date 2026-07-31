package org.ruoyi.service.fault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.service.fault.model.FaultKnowledgeAnswerDraft;
import org.ruoyi.service.fault.model.FaultKnowledgeFacts;
import org.ruoyi.service.fault.model.FaultRequestPlan;
import org.ruoyi.service.fault.model.FaultTaskType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FaultAnswerGeneratorTest {

    @Test
    void sendsOnlyStructuredKnowledgeFactsInJsonMode() throws Exception {
        ChatModel model = model("""
            {"faults":[{"faultCode":"F07561","summary":"编码器参数设置不正确。","causes":["p0421 设置错误。"],"firstCheck":"检查 p0421。","actionsByFaultValue":[],"actions":["修正参数。"],"parameters":["p0421"],"notes":[]}]}
            """);
        FaultRequestPlan plan = new FaultRequestPlan(List.of(FaultTaskType.EXPLAIN_FAULT_CODE),
            null, null, null, null, null, List.of("F07561"), null, List.of("原因", "解决方法"));
        FaultKnowledgeFacts facts = new FaultKnowledgeFacts("F07561", "编码器配置错误",
            "p0421 设置错误。", "修正参数。", null, null, List.of(), null);

        FaultKnowledgeAnswerDraft result =
            new FaultAnswerGenerator(new ObjectMapper()).generateKnowledgeDraft(
                model, "F07561 怎么处理？", plan, List.of(facts), null);

        ArgumentCaptor<dev.langchain4j.model.chat.request.ChatRequest> captor =
            ArgumentCaptor.forClass(dev.langchain4j.model.chat.request.ChatRequest.class);
        verify(model).chat(captor.capture());
        assertEquals(ResponseFormat.JSON, captor.getValue().responseFormat());
        assertEquals(0.0, captor.getValue().temperature());
        String input = ((UserMessage) captor.getValue().messages().get(1)).singleText();
        JsonNode json = new ObjectMapper().readTree(input);
        assertEquals("KNOWLEDGE_LOOKUP", json.get("mode").asText());
        assertFalse(json.get("telemetryRead").asBoolean());
        assertEquals("F07561", json.get("facts").get(0).get("faultCode").asText());
        assertFalse(input.contains("知识片段"));
        assertFalse(input.contains("本次遥测"));
        assertEquals("F07561", result.faults().get(0).faultCode());
    }

    @Test
    void rejectsNonJsonModelAnswer() {
        ChatModel model = model("下面是答案：F07561");
        FaultRequestPlan plan = new FaultRequestPlan(List.of(FaultTaskType.EXPLAIN_FAULT_CODE),
            null, null, null, null, null, List.of("F07561"), null, List.of());

        assertThrows(FaultAnswerGenerator.InvalidAnswerException.class,
            () -> new FaultAnswerGenerator(new ObjectMapper()).generateKnowledgeDraft(
                model, "F07561", plan, List.of(), null));
    }

    private static ChatModel model(String text) {
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from(text));
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenReturn(response);
        return model;
    }
}
