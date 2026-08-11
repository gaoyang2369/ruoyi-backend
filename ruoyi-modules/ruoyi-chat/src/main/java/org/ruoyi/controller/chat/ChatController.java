package org.ruoyi.controller.chat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.dto.request.AgentChatRequest;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.dto.request.ChatVoiceStatusRequest;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.service.chat.impl.ChatServiceFacade;
import org.ruoyi.websocket.chat.sync.ChatSyncEvent;
import org.ruoyi.websocket.chat.sync.ChatSyncEventPublisher;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


/**
 * 聊天管理
 *
 * @author ageerle@163.com
 * @date 2023-03-01
 */
@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/chat")
public class ChatController {

    private final ChatServiceFacade chatService;
    private final ChatSyncEventPublisher chatSyncEventPublisher;

    /**
     * 聊天接口
     */
    @PostMapping("/send")
    @ResponseBody
    public SseEmitter sseChat(@RequestBody @Valid ChatRequest chatRequest, HttpServletResponse response) {
        // 禁止浏览器和反向代理缓存/缓冲 SSE，确保每个事件立即转发给前端。
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return chatService.sseChat(chatRequest);
    }

    /**
     * 仅将语音侧状态转发给当前会话的 Web Chat 订阅者，不触发任何聊天或模型调用。
     */
    @PostMapping("/voice/status")
    @ResponseBody
    public R<Void> voiceStatus(@RequestBody @Valid ChatVoiceStatusRequest request) {
        chatSyncEventPublisher.publish(ChatSyncEvent.voiceStatus(
            request.getSessionId(), request.getClientRequestId(), request.getStatus(), request.getContent()));
        return R.ok();
    }

}
