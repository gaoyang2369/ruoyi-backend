package org.ruoyi.service.fault.model;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** SSE 报告卡片所需的稳定摘要，不包含完整报告正文。 */
public record FaultReportAttachment(
    String reportCode,
    String title,
    String deviceName,
    String inverterName,
    LocalDateTime windowStart,
    LocalDateTime windowEnd,
    String reportStatus,
    String currentStatus,
    String periodStatus,
    double dataCompleteness
) {
    public Map<String, Object> toEventData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportCode", reportCode);
        data.put("title", title);
        data.put("deviceName", deviceName);
        data.put("inverterName", inverterName);
        data.put("windowStart", windowStart == null ? null : windowStart.toString());
        data.put("windowEnd", windowEnd == null ? null : windowEnd.toString());
        data.put("reportStatus", reportStatus);
        data.put("currentStatus", currentStatus);
        data.put("periodStatus", periodStatus);
        data.put("dataCompleteness", dataCompleteness);
        return data;
    }
}
