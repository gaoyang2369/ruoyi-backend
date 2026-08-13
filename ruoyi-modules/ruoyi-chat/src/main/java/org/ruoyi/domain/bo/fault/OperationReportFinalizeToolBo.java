package org.ruoyi.domain.bo.fault;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.ruoyi.fault.report.OperationReportResult;

/** Hermes 内部工具的 finalize 请求。narrative 已由 Hermes 生成，后端只做事实校验和快照写回。 */
@Data
public class OperationReportFinalizeToolBo {

    @NotBlank(message = "报告编号不能为空")
    private String reportId;

    @NotNull(message = "报告叙事不能为空")
    private OperationReportResult.ReportNarrative narrative;

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotBlank(message = "租户不能为空")
    private String tenantId;
}
