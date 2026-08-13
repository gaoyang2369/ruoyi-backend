package org.ruoyi.service.fault.model;

/** 报告聊天分支的短正文、可选附件与模型归纳状态。 */
public record FaultReportChatResult(
    String content,
    FaultReportAttachment attachment,
    boolean llmNarrativeGenerated
) {
}
