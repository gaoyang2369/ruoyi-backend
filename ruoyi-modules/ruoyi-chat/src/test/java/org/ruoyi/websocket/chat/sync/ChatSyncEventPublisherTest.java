package org.ruoyi.websocket.chat.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSyncEventPublisherTest {

    @Test
    void publishesOnlyToSubscribersOfTheEventSession() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ChatSyncEventPublisher publisher = new ChatSyncEventPublisher(objectMapper);
        WebSocketSession matching = mock(WebSocketSession.class);
        WebSocketSession other = mock(WebSocketSession.class);
        when(matching.isOpen()).thenReturn(true);
        when(other.isOpen()).thenReturn(true);

        publisher.register(100L, matching);
        publisher.register(200L, other);
        publisher.publish(ChatSyncEvent.assistantDelta(100L, "request-1", "增量回复"));

        ArgumentCaptor<TextMessage> message = ArgumentCaptor.forClass(TextMessage.class);
        verify(matching).sendMessage(message.capture());
        verify(other, never()).sendMessage(org.mockito.ArgumentMatchers.any());
        JsonNode payload = objectMapper.readTree(message.getValue().getPayload());
        assertEquals("ASSISTANT_DELTA", payload.path("type").asText());
        assertEquals(100L, payload.path("sessionId").asLong());
        assertEquals("request-1", payload.path("clientRequestId").asText());
        assertEquals("增量回复", payload.path("content").asText());
    }
}
