package org.ruoyi.service.fault.model;

/** 确定性报告聊天分支的短正文与可选附件。 */
public record FaultReportChatResult(String content, FaultReportAttachment attachment) {
}
