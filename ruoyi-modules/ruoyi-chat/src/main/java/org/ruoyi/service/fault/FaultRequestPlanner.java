package org.ruoyi.service.fault;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.service.fault.model.FaultRequestPlan;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将自然语言收敛为受限计划；它不参与诊断、知识库选择或权限判断。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaultRequestPlanner {
    private static final Pattern EXPLICIT_FAULT_CODE =
        Pattern.compile("(?i)(?<![A-Z0-9_-])([FA]\\d{3,})(?![A-Z0-9_-])");
    private static final Pattern EXPLANATION_INTENT =
        Pattern.compile("什么|原因|解决|处理|含义|解释|怎么|如何|排查|说明|意思|办法|措施");
    private static final Pattern TELEMETRY_DIAGNOSIS_INTENT =
        Pattern.compile("诊断|遥测|趋势|波形|运行数据|历史数据|最近\\s*\\d+|过去\\s*\\d+|开始时间|结束时间");
    private static final int LOG_RESPONSE_LIMIT = 800;
    private static final String SYSTEM = """
        你是故障诊断请求规划器，不负责诊断或回答。只允许 tasks 中的 DIAGNOSE、EXPLAIN_FAULT_CODE，可拆分复合请求。
        只能输出 JSON 对象，字段为 tasks,deviceName,inverterName,recentMinutes,startTime,endTime,faultCodes,symptom,requestedAspects。
        不得输出 SQL、表名、字段名、工具、用户、租户、角色、知识库ID，不得判断根因或编造设备/逆变器；不确定字段写 null。
        """;
    private final ObjectMapper objectMapper;

    public FaultRequestPlan plan(ChatModel chatModel, List<ChatMessage> history, LocalDateTime now, String timezone,
                                 int defaultWindowMinutes, List<String> allowedAssets, String question, String requestId) {
        if (chatModel == null) throw new ServiceException("故障诊断模型不可用");
        FaultRequestPlan deterministic = planExplicitFaultCodeExplanation(question);
        if (deterministic != null) {
            log.info("fault request planned deterministically: requestId={}, tasks={}, faultCodes={}",
                requestId, deterministic.tasks(), deterministic.faultCodes());
            return deterministic;
        }
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
        log.warn("fault request plan parse failed: requestId={}, firstResponse={}, repairedResponse={}",
            requestId, responseSnippet(response), responseSnippet(repaired));
        throw new ServiceException("无法理解故障诊断请求，请换一种方式描述设备、时间和问题");
    }

    /**
     * 明确的故障码含义/原因/处理查询不依赖 LLM，避免模型格式漂移阻断最常用的知识查询。
     * 包含遥测、时间窗口或显式诊断意图的复合请求仍交给模型规划。
     */
    private static FaultRequestPlan planExplicitFaultCodeExplanation(String question) {
        if (StringUtils.isBlank(question) || !EXPLANATION_INTENT.matcher(question).find()
            || TELEMETRY_DIAGNOSIS_INTENT.matcher(question).find()) {
            return null;
        }
        Matcher matcher = EXPLICIT_FAULT_CODE.matcher(question);
        Set<String> codes = new LinkedHashSet<>();
        while (matcher.find()) {
            codes.add(matcher.group(1).toUpperCase(Locale.ROOT));
        }
        if (codes.isEmpty()) return null;
        return new FaultRequestPlan(List.of(org.ruoyi.service.fault.model.FaultTaskType.EXPLAIN_FAULT_CODE),
            null, null, null, null, null, List.copyOf(codes), null, requestedAspects(question));
    }

    private static List<String> requestedAspects(String question) {
        List<String> aspects = new ArrayList<>();
        if (question.contains("原因") || question.contains("为什么")) aspects.add("原因");
        if (question.contains("解决") || question.contains("处理") || question.contains("怎么")
            || question.contains("如何") || question.contains("排查") || question.contains("办法")
            || question.contains("措施")) {
            aspects.add("解决方法");
        }
        if (aspects.isEmpty()) aspects.add("含义");
        return List.copyOf(aspects);
    }

    private FaultRequestPlan parse(String response) {
        if (response == null || response.isBlank()) return null;
        String json = unwrapSingleJsonFence(response.trim());
        try {
            // 明确忽略越权字段；这些字段既不进入计划，也不会影响后续服务端上下文。
            return objectMapper.readerFor(FaultRequestPlan.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json);
        } catch (JsonProcessingException e) {
            log.debug("fault request JSON parse error: error={}, response={}",
                e.getOriginalMessage(), responseSnippet(response));
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
            ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .temperature(0.0)
                .responseFormat(ResponseFormat.JSON)
                .build();
            ChatResponse response = model.chat(request);
            if (response == null || response.aiMessage() == null || StringUtils.isBlank(response.aiMessage().text())) return null;
            return response.aiMessage().text().trim();
        } catch (RuntimeException ex) {
            log.warn("fault request planning model call failed: requestId={}, error={}",
                requestId, ex.toString(), ex);
            return null;
        }
    }

    private static String responseSnippet(String response) {
        if (response == null) return "<null>";
        String singleLine = response.replaceAll("[\\r\\n\\t]+", " ").trim();
        return singleLine.length() <= LOG_RESPONSE_LIMIT
            ? singleLine
            : singleLine.substring(0, LOG_RESPONSE_LIMIT) + "...";
    }
}
