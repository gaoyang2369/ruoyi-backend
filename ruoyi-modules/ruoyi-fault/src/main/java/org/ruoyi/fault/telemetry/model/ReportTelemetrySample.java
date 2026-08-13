package org.ruoyi.fault.telemetry.model;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 运行报告内部使用的已过滤、去重遥测采样投影。
 * <p>
 * 该结构只在一次报告生成链路中用于确定性分析，不作为 HTTP 响应或模型输入传输原始遥测。
 */
public record ReportTelemetrySample(LocalDateTime observedAt, Map<String, Double> metrics) {

    public ReportTelemetrySample {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
