package org.ruoyi.common.chat.domain.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 语音客户端状态同步请求。
 */
@Data
public class ChatVoiceStatusRequest {

    @NotNull(message = "会话id不能为空")
    private Long sessionId;

    @NotBlank(message = "语音状态不能为空")
    private String status;

    private String clientRequestId;

    /** 可选的状态说明，例如识别中提示文本。 */
    private String content;
}
