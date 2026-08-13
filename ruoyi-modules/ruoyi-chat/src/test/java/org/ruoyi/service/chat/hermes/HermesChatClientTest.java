package org.ruoyi.service.chat.hermes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.ruoyi.config.HermesChatProperties;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesChatException;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesChatResult;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesMessage;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesToolProgress;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesChatClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void streamsSafeContentForwardsParsedToolProgressAndSendsRuoYiManagedHistory() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sse("""
                : keepalive

                data: {"choices":[{"delta":{"content":"您好<th"},"finish_reason":null}]}

                event: hermes.tool.progress
                data: {"toolName":"query_device_status","status":"running","result":{"secret":"ignore"}}

                data: {"choices":[{"delta":{"content":"ink>内部推理"},"finish_reason":null}]}

                data: {"choices":[{"delta":{"reasoning_content":"也不能转发","content":"</think>，诊断完成"},"finish_reason":"stop"}]}

                data: [DONE]

                """));
            server.start();

            HermesChatClient client = client(server);
            List<String> content = new ArrayList<>();
            List<HermesToolProgress> progress = new ArrayList<>();
            HermesChatResult result = client.open(List.of(
                new HermesMessage("system", "仅回答故障诊断问题"),
                new HermesMessage("user", "上一轮问题"),
                new HermesMessage("assistant", "上一轮回答"),
                new HermesMessage("user", "本次问题")))
                .consume(listener(content, progress));

            assertEquals("您好，诊断完成", result.content());
            assertEquals(List.of("您好", "，诊断完成"), content);
            assertEquals(List.of(new HermesToolProgress("query_device_status")), progress);
            assertFalse(result.content().contains("内部推理"));
            assertFalse(result.content().contains("think"));

            RecordedRequest request = server.takeRequest();
            assertEquals("/v1/chat/completions", request.getPath());
            assertEquals("Bearer test-secret", request.getHeader("Authorization"));
            assertEquals("text/event-stream", request.getHeader("Accept"));
            assertEquals(null, request.getHeader("X-Hermes-Session-Id"));
            JsonNode body = objectMapper.readTree(request.getBody().readUtf8());
            assertEquals("fault", body.path("model").asText());
            assertTrue(body.path("stream").asBoolean());
            assertEquals(4, body.path("messages").size());
            assertEquals("system", body.at("/messages/0/role").asText());
            assertEquals("本次问题", body.at("/messages/3/content").asText());
        }
    }

    @Test
    void finishReasonErrorBecomesSafeExceptionWithoutUpstreamBody() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sse("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"error\"}]}\n\n"));
            server.start();

            HermesChatException exception = assertThrows(HermesChatException.class,
                () -> client(server).open(List.of(new HermesMessage("user", "test")))
                    .consume(listener(new ArrayList<>(), new ArrayList<>())));

            assertEquals("Hermes 服务调用失败，请稍后重试", exception.getMessage());
            assertFalse(exception.getMessage().contains("test-secret"));
        }
    }

    private HermesChatClient client(MockWebServer server) {
        HermesChatProperties properties = new HermesChatProperties();
        properties.setBaseUrl(server.url("/v1/").toString());
        properties.setApiKey("test-secret");
        properties.setModel("fault");
        return new HermesChatClient(properties, objectMapper);
    }

    private static HermesChatClient.HermesStreamListener listener(List<String> content, List<HermesToolProgress> progress) {
        return new HermesChatClient.HermesStreamListener() {
            @Override
            public void onContent(String value) {
                content.add(value);
            }

            @Override
            public void onToolProgress(HermesToolProgress value) {
                progress.add(value);
            }
        };
    }

    private static MockResponse sse(String body) {
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body);
    }
}
