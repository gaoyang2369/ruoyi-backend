package org.ruoyi.domain.bo.fault;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 运行报告生成请求。时间均为空时使用默认窗口；知识库范围只能从 Agent 绑定配置解析。
 */
@Data
public class OperationReportGenerateBo {

    @NotNull(message = "Agent ID不能为空")
    private Long agentId;

    /** 可选聊天会话；从聊天生成报告时用于关联附件。 */
    private Long sessionId;

    @NotBlank(message = "设备名称不能为空")
    private String deviceName;

    /** 可选；为空时由遥测数据确定性补全。 */
    private String inverterName;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
