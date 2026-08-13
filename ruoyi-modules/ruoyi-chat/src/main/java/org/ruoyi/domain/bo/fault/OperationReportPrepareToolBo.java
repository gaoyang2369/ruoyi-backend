package org.ruoyi.domain.bo.fault;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Hermes 内部工具的 prepare 请求，复用网页报告请求并显式传递快照所属上下文。 */
@Data
public class OperationReportPrepareToolBo {

    @Valid
    @NotNull(message = "报告请求不能为空")
    private OperationReportGenerateBo report;

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotBlank(message = "租户不能为空")
    private String tenantId;
}
