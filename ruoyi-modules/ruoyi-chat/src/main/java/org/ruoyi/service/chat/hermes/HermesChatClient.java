package org.ruoyi.service.chat.hermes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.config.HermesChatProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal OpenAI Chat Completions SSE client for Hermes.
 *
 * <p>Hermes session headers are deliberately not supported here: RuoYi owns the
 * conversation history and sends it in every request.</p>
 */
@Component
public class HermesChatClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final HermesChatProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    @Autowired
    public HermesChatClient(HermesChatProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // A running fault tool can take longer than a normal completion; SSE has no fixed read deadline.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build());
    }

    public String modelName() {
        return properties.getModel();
    }

    HermesChatClient(HermesChatProperties properties, ObjectMapper objectMapper, OkHttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public HermesStream open(List<HermesMessage> messages) {
        return open(messages, null);
    }

    /**
     * 仅在需要调用受用户归属保护的内部工具时转发本轮 RuoYi 上下文。
     * 该上下文位于 HTTP 头，不进入模型消息或工具参数。
     */
    public HermesStream open(List<HermesMessage> messages, HermesRequestContext requestContext) {
        if (StringUtils.isBlank(properties.getApiKey())) {
            throw new HermesChatException("Hermes 服务尚未配置");
        }
        return new Stream(messages == null ? List.of() : List.copyOf(messages), requestContext);
    }

    /**
     * 报告叙事专用的单次非流式调用。调用方只传确定性报告事实；本方法不附加工具或会话上下文。
     */
    public HermesChatResult complete(List<HermesMessage> messages) {
        if (StringUtils.isBlank(properties.getApiKey())) {
            throw new HermesChatException("Hermes 服务尚未配置");
        }
        Request request = buildRequest(messages == null ? List.of() : List.copyOf(messages), false);
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new HermesChatException("Hermes 服务调用失败，请稍后重试");
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (StringUtils.isBlank(content)) {
                throw new HermesChatException("Hermes 非流式响应格式错误");
            }
            return new HermesChatResult(content);
        } catch (HermesChatException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new HermesChatException("Hermes 服务调用失败，请稍后重试", e);
        }
    }

    public record HermesMessage(String role, String content) {
    }

    public record HermesChatResult(String content) {
    }

    public record HermesRequestContext(Long agentId, Long sessionId, Long userId, String tenantId) {
        boolean complete() {
            return agentId != null && sessionId != null && userId != null && StringUtils.isNotBlank(tenantId);
        }
    }

    /** A safe, frontend-facing tool progress signal. It deliberately carries no tool output. */
    public record HermesToolProgress(String toolName) {
    }

    public interface HermesStream {
        HermesChatResult consume(HermesStreamListener listener);

        void cancel();
    }

    public interface HermesStreamListener {
        void onContent(String content);

        void onToolProgress(HermesToolProgress progress);
    }

    /** Exception messages are safe for the frontend and never carry upstream response bodies. */
    public static class HermesChatException extends RuntimeException {
        public HermesChatException(String message) {
            super(message);
        }

        public HermesChatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class HermesChatCancelledException extends HermesChatException {
        public HermesChatCancelledException() {
            super("Hermes 请求已取消");
        }
    }

    private final class Stream implements HermesStream {
        private final List<HermesMessage> messages;
        private final HermesRequestContext requestContext;
        private final AtomicReference<Call> call = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Stream(List<HermesMessage> messages, HermesRequestContext requestContext) {
            this.messages = messages;
            this.requestContext = requestContext;
        }

        @Override
        public HermesChatResult consume(HermesStreamListener listener) {
            if (cancelled.get()) {
                throw new HermesChatCancelledException();
            }
            Call requestCall = httpClient.newCall(buildRequest(messages, true, requestContext));
            call.set(requestCall);
            if (cancelled.get()) {
                requestCall.cancel();
                throw new HermesChatCancelledException();
            }

            try (Response response = requestCall.execute()) {
                if (!response.isSuccessful()) {
                    throw new HermesChatException("Hermes 服务调用失败，请稍后重试");
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new HermesChatException("Hermes 服务调用失败，请稍后重试");
                }
                return consumeLines(body, listener);
            } catch (IOException e) {
                if (cancelled.get()) {
                    throw new HermesChatCancelledException();
                }
                throw new HermesChatException("Hermes 服务调用失败，请稍后重试", e);
            } finally {
                call.compareAndSet(requestCall, null);
            }
        }

        private HermesChatResult consumeLines(ResponseBody body, HermesStreamListener listener) throws IOException {
            StringBuilder answer = new StringBuilder();
            ThinkTagFilter thinkTagFilter = new ThinkTagFilter();
            String currentEvent = null;
            try (BufferedReader reader = new BufferedReader(body.charStream())) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled.get()) {
                        throw new HermesChatCancelledException();
                    }
                    if (line.isEmpty()) {
                        currentEvent = null;
                        continue;
                    }
                    if (line.startsWith(":")) {
                        continue; // keepalive comment
                    }
                    if (line.startsWith("event:")) {
                        currentEvent = line.substring("event:".length()).trim();
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("hermes.tool.progress".equals(currentEvent)) {
                        listener.onToolProgress(parseToolProgress(data));
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        emitVisibleContent(thinkTagFilter.finish(), answer, listener);
                        return new HermesChatResult(answer.toString());
                    }
                    appendContent(data, thinkTagFilter, answer, listener);
                }
            }
            throw new HermesChatException("Hermes 流式响应异常结束");
        }

        private HermesToolProgress parseToolProgress(String data) {
            try {
                JsonNode root = objectMapper.readTree(data);
                JsonNode tool = root.path("tool");
                String toolName = firstText(root, "toolName", "name", "tool");
                if (tool.isObject()) {
                    toolName = firstNonBlank(toolName, firstText(tool, "toolName", "name", "tool"));
                }
                return new HermesToolProgress(StringUtils.defaultIfBlank(toolName, "Hermes 工具"));
            } catch (Exception ignored) {
                // Hermes progress is auxiliary. Never turn its raw payload into user-visible content.
                return new HermesToolProgress("Hermes 工具");
            }
        }

        private String firstText(JsonNode node, String... names) {
            for (String name : names) {
                JsonNode value = node.path(name);
                if (value.isTextual() && StringUtils.isNotBlank(value.asText())) {
                    return value.asText();
                }
            }
            return null;
        }

        private String firstNonBlank(String first, String second) {
            return StringUtils.isNotBlank(first) ? first : second;
        }

        private void appendContent(String data, ThinkTagFilter thinkTagFilter, StringBuilder answer,
                                   HermesStreamListener listener) {
            try {
                JsonNode root = objectMapper.readTree(data);
                JsonNode choice = root.path("choices").path(0);
                if ("error".equals(choice.path("finish_reason").asText())) {
                    throw new HermesChatException("Hermes 服务调用失败，请稍后重试");
                }
                JsonNode content = choice.path("delta").path("content");
                if (!content.isMissingNode() && !content.isNull()) {
                    String delta = content.asText();
                    if (!delta.isEmpty()) {
                        emitVisibleContent(thinkTagFilter.filter(delta), answer, listener);
                    }
                }
                // reasoning_content is intentionally ignored: it is internal reasoning, not assistant text.
            } catch (HermesChatException e) {
                throw e;
            } catch (Exception e) {
                throw new HermesChatException("Hermes 流式响应格式错误", e);
            }
        }

        private void emitVisibleContent(String content, StringBuilder answer, HermesStreamListener listener) {
            if (StringUtils.isNotBlank(content)) {
                answer.append(content);
                listener.onContent(content);
            }
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            Call requestCall = call.get();
            if (requestCall != null) {
                requestCall.cancel();
            }
        }
    }

    /**
     * Streaming-safe <think> filter. It retains only a possible tag prefix between chunks,
     * rather than applying a regex to each chunk and risking leaked partial tags or reasoning.
     */
    private static final class ThinkTagFilter {
        private static final String OPEN = "<think>";
        private static final String CLOSE = "</think>";
        private boolean insideThink;
        private String pending = "";

        String filter(String chunk) {
            String value = pending + chunk;
            pending = "";
            StringBuilder visible = new StringBuilder();
            while (!value.isEmpty()) {
                String tag = insideThink ? CLOSE : OPEN;
                int tagIndex = value.indexOf(tag);
                if (tagIndex >= 0) {
                    if (!insideThink) {
                        visible.append(value, 0, tagIndex);
                    }
                    insideThink = !insideThink;
                    value = value.substring(tagIndex + tag.length());
                    continue;
                }
                int prefixLength = tagPrefixSuffixLength(value, tag);
                String stable = value.substring(0, value.length() - prefixLength);
                if (!insideThink) {
                    visible.append(stable);
                }
                pending = value.substring(value.length() - prefixLength);
                break;
            }
            return visible.toString();
        }

        String finish() {
            if (insideThink) {
                pending = "";
                return "";
            }
            String tail = pending;
            pending = "";
            // A trailing partial opening tag is never user-facing content.
            return tagPrefixSuffixLength(tail, OPEN) == tail.length() ? "" : tail;
        }

        private static int tagPrefixSuffixLength(String value, String tag) {
            int max = Math.min(value.length(), tag.length() - 1);
            for (int length = max; length > 0; length--) {
                if (value.endsWith(tag.substring(0, length))) {
                    return length;
                }
            }
            return 0;
        }
    }

    private Request buildRequest(List<HermesMessage> messages, boolean stream) {
        return buildRequest(messages, stream, null);
    }

    private Request buildRequest(List<HermesMessage> messages, boolean stream, HermesRequestContext requestContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("stream", stream);
        payload.put("messages", messages);
        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new HermesChatException("Hermes 请求构建失败", e);
        }
        Request.Builder builder = new Request.Builder()
            .url(endpoint())
            .header("Authorization", "Bearer " + properties.getApiKey())
            .header("Content-Type", "application/json")
            .header("Accept", stream ? "text/event-stream" : "application/json")
            // Intentionally do not send X-Hermes-Session-Id: history belongs to RuoYi.
            .post(RequestBody.create(json, JSON));
        if (requestContext != null && requestContext.complete()) {
            builder.header("X-RuoYi-Agent-Id", String.valueOf(requestContext.agentId()));
            builder.header("X-RuoYi-Chat-Session-Id", String.valueOf(requestContext.sessionId()));
            builder.header("X-RuoYi-User-Id", String.valueOf(requestContext.userId()));
            builder.header("X-RuoYi-Tenant-Id", requestContext.tenantId());
        }
        return builder.build();
    }

    private String endpoint() {
        if (StringUtils.isBlank(properties.getBaseUrl())) {
            throw new HermesChatException("Hermes 服务尚未配置");
        }
        return properties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
    }
}
