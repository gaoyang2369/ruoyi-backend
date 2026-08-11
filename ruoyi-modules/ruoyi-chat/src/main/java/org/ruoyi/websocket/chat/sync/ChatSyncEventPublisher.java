package org.ruoyi.websocket.chat.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按聊天会话维护 WebSocket 订阅，并向订阅者广播同步事件。
 * 不参与聊天请求处理，也不会调用模型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSyncEventPublisher {

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<Long, Set<WebSocketSession>> sessionsByChatSession = new ConcurrentHashMap<>();

    public void register(Long sessionId, WebSocketSession webSocketSession) {
        sessionsByChatSession.computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet()).add(webSocketSession);
    }

    public void unregister(Long sessionId, WebSocketSession webSocketSession) {
        Set<WebSocketSession> sessions = sessionsByChatSession.get(sessionId);
        if (sessions == null) {
            return;
        }
        sessions.remove(webSocketSession);
        if (sessions.isEmpty()) {
            sessionsByChatSession.remove(sessionId, sessions);
        }
    }

    public void publish(ChatSyncEvent event) {
        if (event == null || event.sessionId() == null) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByChatSession.get(event.sessionId());
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("chat-sync 事件序列化失败: sessionId={}", event.sessionId(), e);
            return;
        }
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                unregister(event.sessionId(), session);
                continue;
            }
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(payload));
                    }
                }
            } catch (Exception e) {
                log.debug("chat-sync 事件发送失败: sessionId={}, ws={}", event.sessionId(), session.getId(), e);
                unregister(event.sessionId(), session);
            }
        }
    }
}
