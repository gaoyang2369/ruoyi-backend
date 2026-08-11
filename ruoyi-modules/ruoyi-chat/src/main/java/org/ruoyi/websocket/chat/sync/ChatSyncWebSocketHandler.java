package org.ruoyi.websocket.chat.sync;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 只读会话同步端点。客户端通过握手 query 的 sessionId 订阅，消息不会触发聊天或模型调用。
 */
@Component
@RequiredArgsConstructor
public class ChatSyncWebSocketHandler extends AbstractWebSocketHandler {

    private final ChatSyncEventPublisher eventPublisher;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long sessionId = sessionId(session);
        if (sessionId != null) {
            eventPublisher.register(sessionId, session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        Long sessionId = sessionId(session);
        if (sessionId != null) {
            eventPublisher.unregister(sessionId, session);
        }
    }

    private static Long sessionId(WebSocketSession session) {
        Object value = session.getAttributes().get(ChatSyncHandshakeInterceptor.SESSION_ID_KEY);
        return value instanceof Long sessionId ? sessionId : null;
    }
}
