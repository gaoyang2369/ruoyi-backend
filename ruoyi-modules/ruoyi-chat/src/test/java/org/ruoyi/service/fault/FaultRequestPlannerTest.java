package org.ruoyi.service.fault;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.service.fault.model.FaultTaskType;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class FaultRequestPlannerTest {
    @Test
    void parsesCompositePlanAndDropsNonPlanFields() {
        ChatModel model = model("""
            {"tasks":["DIAGNOSE","EXPLAIN_FAULT_CODE"],"deviceName":"G120","inverterName":"INV-1","recentMinutes":30,"faultCodes":["f30005"],"knowledgeBaseIds":[99],"userId":1}
            """);
        var plan = planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of("G120"), "问题", "r1");
        assertEquals(List.of(FaultTaskType.DIAGNOSE, FaultTaskType.EXPLAIN_FAULT_CODE), plan.tasks());
        assertEquals(List.of("f30005"), plan.faultCodes());
    }

    @Test
    void acceptsSingleJsonFenceAndRetriesOnlyOnce() {
        ChatModel model = mock(ChatModel.class);
        ChatResponse bad = response("这不是 JSON");
        ChatResponse good = response("```json\n{\"tasks\":[\"EXPLAIN_FAULT_CODE\"],\"faultCodes\":[\"F30005\"]}\n```");
        when(model.chat(anyList())).thenReturn(bad, good);
        var plan = planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of(), "问题", "r2");
        assertEquals(List.of(FaultTaskType.EXPLAIN_FAULT_CODE), plan.tasks());
        org.mockito.Mockito.verify(model, times(2)).chat(anyList());
    }

    @Test
    void doesNotExtractJsonFromExplanatoryText() {
        ChatModel model = model("说明：{\"tasks\":[\"DIAGNOSE\"]}，请确认");
        assertThrows(ServiceException.class, () -> planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of(), "问题", "r3"));
    }

    @Test
    void retriesOnceWhenFirstResponseIsNull() {
        ChatModel model = mock(ChatModel.class);
        ChatResponse repaired = response("{\"tasks\":[\"EXPLAIN_FAULT_CODE\"],\"faultCodes\":[\"F30005\"]}");
        when(model.chat(anyList())).thenReturn(null, repaired);
        assertEquals(List.of(FaultTaskType.EXPLAIN_FAULT_CODE), planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of(), "问题", "r4").tasks());
        org.mockito.Mockito.verify(model, times(2)).chat(anyList());
    }

    @Test
    void convertsModelFailuresToSingleBusinessException() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenThrow(new IllegalStateException("provider failure"));
        ServiceException error = assertThrows(ServiceException.class, () -> planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of(), "问题", "r5"));
        assertEquals("无法理解故障诊断请求，请换一种方式描述设备、时间和问题", error.getMessage());
        org.mockito.Mockito.verify(model, times(2)).chat(anyList());
    }

    @Test
    void appendsQuestionWhenHistoryDoesNotEndWithCurrentUserQuestion() {
        ChatModel model = model("{\"tasks\":[\"EXPLAIN_FAULT_CODE\"],\"faultCodes\":[\"F30005\"]}");
        planner().plan(model, List.of(UserMessage.from("旧问题")), now(), "Asia/Shanghai", 30, List.of(), "新问题", "r6");
        @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<List<ChatMessage>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(model).chat(captor.capture());
        assertEquals("新问题", ((UserMessage) captor.getValue().get(captor.getValue().size() - 1)).singleText());
    }

    @Test
    void doesNotDuplicateCurrentQuestionWhenHistoryEndsWithIt() {
        ChatModel model = model("{\"tasks\":[\"EXPLAIN_FAULT_CODE\"],\"faultCodes\":[\"F30005\"]}");
        planner().plan(model, List.of(UserMessage.from("当前 问题")), now(), "Asia/Shanghai", 30, List.of(), "当前 问题", "r7");
        @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<List<ChatMessage>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(model).chat(captor.capture());
        long currentQuestionCount = captor.getValue().stream().filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast).map(UserMessage::singleText).filter("当前 问题"::equals).count();
        assertEquals(1, currentQuestionCount);
    }

    private static FaultRequestPlanner planner() { return new FaultRequestPlanner(new ObjectMapper()); }
    private static LocalDateTime now() { return LocalDateTime.of(2026, 7, 27, 10, 0); }
    private static ChatModel model(String text) {
        ChatResponse response = response(text);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenReturn(response);
        return model;
    }
    private static ChatResponse response(String text) { ChatResponse response = mock(ChatResponse.class); when(response.aiMessage()).thenReturn(AiMessage.from(text)); return response; }
}
