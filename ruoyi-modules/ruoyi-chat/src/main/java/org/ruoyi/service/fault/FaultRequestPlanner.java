package org.ruoyi.service.fault;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.service.fault.model.FaultRequestPlan;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 将自然语言收敛为受限计划；它不参与诊断、知识库选择或权限判断。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaultRequestPlanner {
    private static final String SYSTEM = """
        你是故障诊断请求规划器，不负责诊断或回答。只允许 tasks 中的 DIAGNOSE、EXPLAIN_FAULT_CODE，可拆分复合请求。
        只能输出 JSON 对象，字段为 tasks,deviceName,inverterName,recentMinutes,startTime,endTime,faultCodes,symptom,requestedAspects。
        不得输出 SQL、表名、字段名、工具、用户、租户、角色、知识库ID，不得判断根因或编造设备/逆变器；不确定字段写 null。
        """;
    private final ObjectMapper objectMapper;

    public FaultRequestPlan plan(ChatModel chatModel, List<ChatMessage> history, LocalDateTime now, String timezone,
                                 int defaultWindowMinutes, List<String> allowedAssets, String question, String requestId) {
        if (chatModel == null) throw new ServiceException("故障诊断模型不可用");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM + "业务当前时间=" + now + "；时区=" + timezone
            + "；默认窗口分钟=" + defaultWindowMinutes + "；允许资产=" + (allowedAssets == null ? List.of() : allowedAssets)));
        if (history != null) messages.addAll(history);
        // 只有最后一条确为当前用户问题时才不重复；历史异常时不能丢掉本轮问题。
        if (!historyContainsCurrentQuestion(history, question)) messages.add(UserMessage.from(question == null ? "" : question));
        String response = safelyReadResponse(chatModel, messages, requestId);
        FaultRequestPlan parsed = parse(response);
        if (parsed != null) {
            log.info("fault request plan parsed: requestId={}, tasks={}", requestId, parsed.tasks());
            return parsed;
        }
        String repairInput = response == null ? "" : response;
        String repaired = safelyReadResponse(chatModel, List.of(SystemMessage.from(SYSTEM + "以下输出不是合法 JSON。只修复 JSON 格式，不新增事实。"), UserMessage.from(repairInput)), requestId);
        parsed = parse(repaired);
        if (parsed != null) {
            log.info("fault request plan repaired: requestId={}, tasks={}", requestId, parsed.tasks());
            return parsed;
        }
        log.warn("fault request plan parse failed: requestId={}", requestId);
        throw new ServiceException("无法理解故障诊断请求，请换一种方式描述设备、时间和问题");
    }

    private FaultRequestPlan parse(String response) {
        if (response == null || response.isBlank()) return null;
        String json = unwrapSingleJsonFence(response.trim());
        try {
            // 明确忽略越权字段；这些字段既不进入计划，也不会影响后续服务端上下文。
            return objectMapper.readerFor(FaultRequestPlan.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String unwrapSingleJsonFence(String value) {
        if (!value.startsWith("```")) return value;
        int firstNewline = value.indexOf('\n');
        if (firstNewline < 0 || !value.endsWith("```")) return value;
        String language = value.substring(3, firstNewline).trim();
        if (!language.isEmpty() && !"json".equalsIgnoreCase(language)) return value;
        return value.substring(firstNewline + 1, value.length() - 3).trim();
    }

    private static boolean historyContainsCurrentQuestion(List<ChatMessage> history, String question) {
        if (history == null || history.isEmpty() || !(history.get(history.size() - 1) instanceof UserMessage user)) return false;
        return normalizeQuestion(user.singleText()).equals(normalizeQuestion(question));
    }

    private static String normalizeQuestion(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    /** 模型异常和空响应都归一为解析失败，避免内部错误或空值进入修复提示。 */
    private static String safelyReadResponse(ChatModel model, List<ChatMessage> messages, String requestId) {
        try {
            ChatResponse response = model.chat(messages);
            if (response == null || response.aiMessage() == null || StringUtils.isBlank(response.aiMessage().text())) return null;
            return response.aiMessage().text().trim();
        } catch (RuntimeException ex) {
            log.warn("fault request planning model call failed: requestId={}", requestId);
            return null;
        }
    }
}
