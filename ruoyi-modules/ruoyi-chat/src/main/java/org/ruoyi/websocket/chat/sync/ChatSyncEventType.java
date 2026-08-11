package org.ruoyi.websocket.chat.sync;

/** 语音与 Web Chat 会话同步事件类型。 */
public enum ChatSyncEventType {
    USER_MESSAGE,
    TOOL_PROGRESS,
    ASSISTANT_DELTA,
    ASSISTANT_DONE,
    VOICE_STATUS
}
