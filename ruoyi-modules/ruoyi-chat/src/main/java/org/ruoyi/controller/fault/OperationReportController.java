package org.ruoyi.controller.fault;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.utils.file.FileUtils;
import org.ruoyi.common.log.annotation.Log;
import org.ruoyi.common.log.enums.BusinessType;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.common.web.core.BaseController;
import org.ruoyi.domain.bo.fault.OperationReportGenerateBo;
import org.ruoyi.domain.vo.fault.OperationReportVo;
import org.ruoyi.fault.report.MarkdownOperationReportRenderer;
import org.ruoyi.fault.report.OperationReportResult;
import org.ruoyi.service.fault.OperationReportSnapshotService;
import org.ruoyi.service.fault.OperationReportService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/**
 * 设备运行与状态报告接口。生成后立即保存完整结构化快照，详情和下载只读取该快照。
 * 访问控制使用快照的 userId + tenantId 所有权校验，不额外依赖菜单权限。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/fault/report")
public class OperationReportController extends BaseController {

    private static final DateTimeFormatter FILENAME_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OperationReportService operationReportService;

    @PostMapping("/generate")
    public R<OperationReportVo> generate(@Valid @RequestBody OperationReportGenerateBo bo) {
        OperationReportResult result = operationReportService.generate(
            bo, LoginHelper.getUserId(), TenantHelper.getTenantId());
        return R.ok(toVo(result));
    }

    @GetMapping("/{reportCode}")
    public R<OperationReportVo> detail(@PathVariable String reportCode) {
        OperationReportResult result = operationReportService.get(
            reportCode, LoginHelper.getUserId(), TenantHelper.getTenantId());
        return R.ok(toVo(result));
    }

    @Log(title = "运行报告", businessType = BusinessType.EXPORT)
    @GetMapping("/download/{reportCode}")
    public void download(@PathVariable String reportCode, HttpServletResponse response) throws IOException {
        OperationReportResult result = operationReportService.get(
            reportCode, LoginHelper.getUserId(), TenantHelper.getTenantId());
        String markdown = MarkdownOperationReportRenderer.renderFull(result, null);
        String filename = "运行报告_" + result.asset().deviceName() + "_"
            + FILENAME_TIME_FORMATTER.format(result.metadata().generatedAt()) + ".md";
        response.setContentType("text/markdown; charset=utf-8");
        FileUtils.setAttachmentResponseHeader(response, filename);
        response.getOutputStream().write(markdown.getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    private static OperationReportVo toVo(OperationReportResult result) {
        OperationReportVo vo = new OperationReportVo();
        vo.setReportCode(result.metadata().reportId());
        vo.setDeviceName(result.asset().deviceName());
        vo.setInverterName(result.asset().inverterName());
        vo.setHealthStatus(result.periodStatus().name());
        vo.setPeriodStatus(result.periodStatus().name());
        vo.setCurrentStatus(result.currentStatus().name());
        vo.setReportStatus(OperationReportSnapshotService.STATUS_COMPLETED);
        vo.setSummary(result.summary().conclusion());
        vo.setMarkdown(MarkdownOperationReportRenderer.renderFull(result, null));
        vo.setReport(result);
        vo.setGeneratedAt(result.metadata().generatedAt());
        vo.setRequestedStartTime(result.period().windowStart());
        vo.setRequestedEndTime(result.period().windowEnd());
        vo.setFallbackToLatestData(result.period().fallbackToLatestData());
        return vo;
    }

}
