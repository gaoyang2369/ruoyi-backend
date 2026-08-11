package org.ruoyi.websocket.chat.sync;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 解析 /chat/sync/ws?sessionId= 的只读订阅目标。 */
@Component
public class ChatSyncHandshakeInterceptor implements HandshakeInterceptor {

    public static final String SESSION_ID_KEY = "chatSyncSessionId";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Long sessionId = parseSessionId(request.getURI().getRawQuery());
        if (sessionId == null) {
            return false;
        }
        attributes.put(SESSION_ID_KEY, sessionId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private static Long parseSessionId(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0 || !"sessionId".equals(pair.substring(0, separator))) {
                continue;
            }
            try {
                String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
                Long sessionId = Long.valueOf(value);
                return sessionId > 0 ? sessionId : null;
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
