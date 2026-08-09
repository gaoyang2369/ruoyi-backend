package org.ruoyi.fault.controller.dto;

import java.util.List;

/** 故障码知识工具请求。 */
public record FaultCodeRequest(
    String code,
    List<Long> knowledgeBaseIds
) {
}
