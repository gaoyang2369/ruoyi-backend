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
        if (StringUtils.isBlank(properties.getApiKey())) {
            throw new HermesChatException("Hermes 服务尚未配置");
        }
        return new Stream(messages == null ? List.of() : List.copyOf(messages));
    }

    public record HermesMessage(String role, String content) {
    }

    public record HermesChatResult(String content) {
    }

    public interface HermesStream {
        HermesChatResult consume(HermesStreamListener listener);

        void cancel();
    }

    public interface HermesStreamListener {
        void onContent(String content);

        void onToolProgress(String progress);
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
        private final AtomicReference<Call> call = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Stream(List<HermesMessage> messages) {
            this.messages = messages;
        }

        @Override
        public HermesChatResult consume(HermesStreamListener listener) {
            if (cancelled.get()) {
                throw new HermesChatCancelledException();
            }
            Call requestCall = httpClient.newCall(buildRequest(messages));
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
                        listener.onToolProgress(data);
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        return new HermesChatResult(answer.toString());
                    }
                    appendContent(data, answer, listener);
                }
            }
            throw new HermesChatException("Hermes 流式响应异常结束");
        }

        private void appendContent(String data, StringBuilder answer, HermesStreamListener listener) {
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
                        answer.append(delta);
                        listener.onContent(delta);
                    }
                }
            } catch (HermesChatException e) {
                throw e;
            } catch (Exception e) {
                throw new HermesChatException("Hermes 流式响应格式错误", e);
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

    private Request buildRequest(List<HermesMessage> messages) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("stream", true);
        payload.put("messages", messages);
        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new HermesChatException("Hermes 请求构建失败", e);
        }
        return new Request.Builder()
            .url(endpoint())
            .header("Authorization", "Bearer " + properties.getApiKey())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            // Intentionally do not send X-Hermes-Session-Id: history belongs to RuoYi.
            .post(RequestBody.create(json, JSON))
            .build();
    }

    private String endpoint() {
        if (StringUtils.isBlank(properties.getBaseUrl())) {
            throw new HermesChatException("Hermes 服务尚未配置");
        }
        return properties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
    }
}
