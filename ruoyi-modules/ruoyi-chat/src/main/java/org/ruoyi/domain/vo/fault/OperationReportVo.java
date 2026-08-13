package org.ruoyi.domain.vo.fault;

import lombok.Data;
import org.ruoyi.fault.report.OperationReportResult;

import java.time.LocalDateTime;

/**
 * 运行报告接口响应：元信息、Markdown 与同一份完整结构化快照。
 */
@Data
public class OperationReportVo {

    private String reportCode;

    private String deviceName;

    private String inverterName;

    /** 离散健康状态：NORMAL / ATTENTION / FAULT / UNKNOWN。 */
    private String healthStatus;

    private String periodStatus;

    private String currentStatus;

    private String reportStatus;

    /** 服务端确定性生成的运行结论段落。 */
    private String summary;

    /** 渲染后的完整报告正文。 */
    private String markdown;

    private OperationReportResult report;

    private LocalDateTime generatedAt;

    private LocalDateTime requestedStartTime;

    private LocalDateTime requestedEndTime;

    /** 请求窗口无数据时是否回退到了最近可用数据窗口。 */
    private boolean fallbackToLatestData;
}
