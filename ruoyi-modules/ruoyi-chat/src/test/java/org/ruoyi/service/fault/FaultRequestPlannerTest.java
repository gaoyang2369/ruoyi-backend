package org.ruoyi.service.fault;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.service.fault.model.FaultTaskType;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
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
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenReturn(bad, good);
        var plan = planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of(), "问题", "r2");
        assertEquals(List.of(FaultTaskType.EXPLAIN_FAULT_CODE), plan.tasks());
        org.mockito.Mockito.verify(model, times(2)).chat(any(dev.langchain4j.model.chat.request.ChatRequest.class));
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
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenReturn(null, repaired);
        assertEquals(List.of(FaultTaskType.EXPLAIN_FAULT_CODE), planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of(), "问题", "r4").tasks());
        org.mockito.Mockito.verify(model, times(2)).chat(any(dev.langchain4j.model.chat.request.ChatRequest.class));
    }

    @Test
    void convertsModelFailuresToSingleBusinessException() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenThrow(new IllegalStateException("provider failure"));
        ServiceException error = assertThrows(ServiceException.class, () -> planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of(), "问题", "r5"));
        assertEquals("无法理解故障诊断请求，请换一种方式描述设备、时间和问题", error.getMessage());
        org.mockito.Mockito.verify(model, times(2)).chat(any(dev.langchain4j.model.chat.request.ChatRequest.class));
    }

    @Test
    void appendsQuestionWhenHistoryDoesNotEndWithCurrentUserQuestion() {
        ChatModel model = model("{\"tasks\":[\"EXPLAIN_FAULT_CODE\"],\"faultCodes\":[\"F30005\"]}");
        planner().plan(model, List.of(UserMessage.from("旧问题")), now(), "Asia/Shanghai", 30, List.of(), "新问题", "r6");
        org.mockito.ArgumentCaptor<dev.langchain4j.model.chat.request.ChatRequest> requestCaptor =
            org.mockito.ArgumentCaptor.forClass(dev.langchain4j.model.chat.request.ChatRequest.class);
        org.mockito.Mockito.verify(model).chat(requestCaptor.capture());
        List<ChatMessage> messages = requestCaptor.getValue().messages();
        assertEquals("新问题", ((UserMessage) messages.get(messages.size() - 1)).singleText());
        assertEquals(ResponseFormat.JSON, requestCaptor.getValue().responseFormat());
        assertEquals(0.0, requestCaptor.getValue().temperature());
    }

    @Test
    void doesNotDuplicateCurrentQuestionWhenHistoryEndsWithIt() {
        ChatModel model = model("{\"tasks\":[\"EXPLAIN_FAULT_CODE\"],\"faultCodes\":[\"F30005\"]}");
        planner().plan(model, List.of(UserMessage.from("当前 问题")), now(), "Asia/Shanghai", 30, List.of(), "当前 问题", "r7");
        org.mockito.ArgumentCaptor<dev.langchain4j.model.chat.request.ChatRequest> captor =
            org.mockito.ArgumentCaptor.forClass(dev.langchain4j.model.chat.request.ChatRequest.class);
        org.mockito.Mockito.verify(model).chat(captor.capture());
        long currentQuestionCount = captor.getValue().messages().stream().filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast).map(UserMessage::singleText).filter("当前 问题"::equals).count();
        assertEquals(1, currentQuestionCount);
    }

    @Test
    void plansExplicitFaultCodeExplanationWithoutCallingModel() {
        ChatModel model = mock(ChatModel.class);

        var plan = planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of(),
            "F07561是什么原因？以及如何解决呀？", "r8");

        assertEquals(List.of(FaultTaskType.EXPLAIN_FAULT_CODE), plan.tasks());
        assertEquals(List.of("F07561"), plan.faultCodes());
        assertEquals(List.of("原因", "解决方法"), plan.requestedAspects());
        verifyNoInteractions(model);
    }

    @Test
    void plansBareFaultCodeWithoutModel() {
        var plan = planner().plan(null, List.of(), now(), "Asia/Shanghai", 30, List.of(),
            "F07561", "r10");

        assertEquals(List.of(FaultTaskType.EXPLAIN_FAULT_CODE), plan.tasks());
        assertEquals(List.of("F07561"), plan.faultCodes());
        assertEquals(List.of("含义"), plan.requestedAspects());
    }

    @Test
    void plansMultipleBareFaultCodesWithoutModel() {
        var plan = planner().plan(null, List.of(), now(), "Asia/Shanghai", 30, List.of(),
            "F07561、A30005 和 f10001？", "r11");

        assertEquals(List.of(FaultTaskType.EXPLAIN_FAULT_CODE), plan.tasks());
        assertEquals(List.of("F07561", "A30005", "F10001"), plan.faultCodes());
    }

    @Test
    void keepsTelemetryDiagnosisWithFaultCodeOnModelPlanningPath() {
        ChatModel model = model("""
            {"tasks":["DIAGNOSE","EXPLAIN_FAULT_CODE"],"deviceName":"G120","inverterName":"INV-1","faultCodes":["F07561"]}
            """);

        var plan = planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of("G120"),
            "请诊断G120最近30分钟的运行数据，并解释F07561", "r9");

        assertEquals(List.of(FaultTaskType.DIAGNOSE, FaultTaskType.EXPLAIN_FAULT_CODE), plan.tasks());
        org.mockito.Mockito.verify(model).chat(any(dev.langchain4j.model.chat.request.ChatRequest.class));
    }

    @Test
    void keepsAssetContextOnModelPlanningPath() {
        ChatModel model = model("""
            {"tasks":["DIAGNOSE","EXPLAIN_FAULT_CODE"],"deviceName":"G120","inverterName":"INV-1","faultCodes":["F07561"]}
            """);

        var plan = planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of("G120"),
            "设备 G120 的逆变器 INV-1 出现 F07561，是什么原因？", "r12");

        assertEquals(List.of(FaultTaskType.DIAGNOSE, FaultTaskType.EXPLAIN_FAULT_CODE), plan.tasks());
        org.mockito.Mockito.verify(model).chat(any(dev.langchain4j.model.chat.request.ChatRequest.class));
    }

    @Test
    void keepsExplicitTimeRangeOnModelPlanningPath() {
        ChatModel model = model("""
            {"tasks":["DIAGNOSE"],"deviceName":"G120","inverterName":"INV-1","startTime":"2026-07-30 10:00:00","endTime":"2026-07-30 11:00:00"}
            """);

        var plan = planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of("G120"),
            "查看 F07561 在 2026-07-30 到 2026-07-31 的情况", "r13");

        assertEquals(List.of(FaultTaskType.DIAGNOSE), plan.tasks());
        org.mockito.Mockito.verify(model).chat(any(dev.langchain4j.model.chat.request.ChatRequest.class));
    }

    @Test
    void parsesObjectStyleTaskEntriesFromModelOutput() {
        // 线上真实漂移：模型把 tasks 写成对象数组，顶层字段同时重复出现在任务对象内。
        ChatModel model = model("""
            {"tasks":[{"type":"DIAGNOSE","deviceName":"G120电机1","inverterName":null,"recentMinutes":30,"startTime":null,"endTime":null,"faultCodes":[],"symptom":"现在运行情况如何","requestedAspects":["运行状态"]}],"deviceName":"G120电机1","inverterName":null,"recentMinutes":30,"startTime":null,"endTime":null,"faultCodes":[],"symptom":"现在运行情况如何","requestedAspects":["运行状态"]}
            """);

        var plan = planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of("G120电机1"),
            "G120电机1现在运行状态如何？", "r14");

        assertEquals(List.of(FaultTaskType.DIAGNOSE), plan.tasks());
        assertEquals("G120电机1", plan.deviceName());
        assertEquals(30, plan.recentMinutes());
        assertEquals("现在运行情况如何", plan.symptom());
        assertEquals(List.of("运行状态"), plan.requestedAspects());
        org.mockito.Mockito.verify(model, times(1)).chat(any(dev.langchain4j.model.chat.request.ChatRequest.class));
    }

    @Test
    void parsesLowercaseSingleTaskValue() {
        ChatModel model = model("{\"tasks\":\"diagnose\",\"deviceName\":\"G120\",\"inverterName\":\"INV-1\"}");

        var plan = planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of("G120"),
            "问题", "r15");

        assertEquals(List.of(FaultTaskType.DIAGNOSE), plan.tasks());
    }

    @Test
    void parsesMixedTaskEntryShapes() {
        ChatModel model = model("{\"tasks\":[{\"type\":\"EXPLAIN_FAULT_CODE\"},\"DIAGNOSE\"],"
            + "\"deviceName\":\"G120\",\"inverterName\":\"INV-1\",\"faultCodes\":[\"F30005\"]}");

        var plan = planner().plan(model, List.of(), now(), "Asia/Shanghai", 30, List.of("G120"),
            "问题", "r16");

        assertEquals(List.of(FaultTaskType.EXPLAIN_FAULT_CODE, FaultTaskType.DIAGNOSE), plan.tasks());
    }

    private static FaultRequestPlanner planner() { return new FaultRequestPlanner(new ObjectMapper()); }
    private static LocalDateTime now() { return LocalDateTime.of(2026, 7, 27, 10, 0); }
    private static ChatModel model(String text) {
        ChatResponse response = response(text);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenReturn(response);
        return model;
    }
    private static ChatResponse response(String text) { ChatResponse response = mock(ChatResponse.class); when(response.aiMessage()).thenReturn(AiMessage.from(text)); return response; }
}
