package org.ruoyi.controller.fault;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.domain.bo.fault.OperationReportFinalizeToolBo;
import org.ruoyi.domain.bo.fault.OperationReportPrepareToolBo;
import org.ruoyi.fault.report.OperationReportResult;
import org.ruoyi.fault.report.PreparedOperationReport;
import org.ruoyi.service.fault.OperationReportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Hermes 调用的运行报告两阶段工具；沿用 /internal/fault-tools 的内部认证边界。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/fault-tools/operation-report")
public class OperationReportToolController {

    private final OperationReportService operationReportService;

    @PostMapping("/prepare")
    public R<PreparedOperationReport> prepareOperationReport(@Valid @RequestBody OperationReportPrepareToolBo request) {
        return R.ok(operationReportService.prepare(request.getReport(), request.getUserId(), request.getTenantId()));
    }

    @PostMapping("/finalize")
    public R<OperationReportResult> finalizeOperationReport(@Valid @RequestBody OperationReportFinalizeToolBo request) {
        return R.ok(operationReportService.finalize(request.getReportId(), request.getNarrative(),
            request.getUserId(), request.getTenantId()));
    }
}
