package org.ruoyi.websocket.chat.sync;

/**
 * 同一聊天会话的轻量实时同步载荷。
 *
 * <p>content 用于消息正文、工具进度或语音状态说明；status 仅用于终态和语音状态。</p>
 */
public record ChatSyncEvent(
    ChatSyncEventType type,
    Long sessionId,
    String clientRequestId,
    String content,
    String status
) {

    public static ChatSyncEvent userMessage(Long sessionId, String clientRequestId, String content) {
        return new ChatSyncEvent(ChatSyncEventType.USER_MESSAGE, sessionId, clientRequestId, content, null);
    }

    public static ChatSyncEvent toolProgress(Long sessionId, String clientRequestId, String content) {
        return new ChatSyncEvent(ChatSyncEventType.TOOL_PROGRESS, sessionId, clientRequestId, content, null);
    }

    public static ChatSyncEvent assistantDelta(Long sessionId, String clientRequestId, String content) {
        return new ChatSyncEvent(ChatSyncEventType.ASSISTANT_DELTA, sessionId, clientRequestId, content, null);
    }

    public static ChatSyncEvent assistantDone(Long sessionId, String clientRequestId, String status) {
        return new ChatSyncEvent(ChatSyncEventType.ASSISTANT_DONE, sessionId, clientRequestId, null, status);
    }

    public static ChatSyncEvent voiceStatus(Long sessionId, String clientRequestId, String status, String content) {
        return new ChatSyncEvent(ChatSyncEventType.VOICE_STATUS, sessionId, clientRequestId, content, status);
    }
}
