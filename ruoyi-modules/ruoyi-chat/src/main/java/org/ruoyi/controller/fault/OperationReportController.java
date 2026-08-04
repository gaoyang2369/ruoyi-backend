package org.ruoyi.controller.fault;

import cn.dev33.satoken.annotation.SaCheckPermission;
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
import org.ruoyi.service.fault.OperationReportService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/**
 * 设备运行与状态报告接口：生成（查看）与下载。报告按需实时生成，不落库。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/fault/report")
public class OperationReportController extends BaseController {

    private static final DateTimeFormatter FILENAME_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OperationReportService operationReportService;

    @SaCheckPermission("fault:report:query")
    @PostMapping("/generate")
    public R<OperationReportVo> generate(@Valid @RequestBody OperationReportGenerateBo bo) {
        OperationReportResult result = operationReportService.generate(
            bo, LoginHelper.getUserId(), TenantHelper.getTenantId());
        return R.ok(toVo(result, render(result, bo.getAgentId())));
    }

    @SaCheckPermission("fault:report:export")
    @Log(title = "运行报告", businessType = BusinessType.EXPORT)
    @GetMapping("/download")
    public void download(@Valid OperationReportGenerateBo bo, HttpServletResponse response) throws IOException {
        OperationReportResult result = operationReportService.generate(
            bo, LoginHelper.getUserId(), TenantHelper.getTenantId());
        String markdown = render(result, bo.getAgentId());
        String filename = "运行报告_" + result.deviceName() + "_"
            + FILENAME_TIME_FORMATTER.format(result.generatedAt()) + ".md";
        response.setContentType("text/markdown; charset=utf-8");
        FileUtils.setAttachmentResponseHeader(response, filename);
        response.getOutputStream().write(markdown.getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    private String render(OperationReportResult result, Long agentId) {
        return MarkdownOperationReportRenderer.render(result, operationReportService.narrate(agentId, result));
    }

    private static OperationReportVo toVo(OperationReportResult result, String markdown) {
        OperationReportVo vo = new OperationReportVo();
        vo.setReportCode(result.reportCode());
        vo.setDeviceName(result.deviceName());
        vo.setInverterName(result.inverterName());
        vo.setHealthStatus(result.healthStatus().name());
        vo.setSummary(result.summary());
        vo.setMarkdown(markdown);
        vo.setGeneratedAt(result.generatedAt());
        vo.setRequestedStartTime(result.requestedStartTime());
        vo.setRequestedEndTime(result.requestedEndTime());
        vo.setFallbackToLatestData(result.telemetry().fallbackToLatestData());
        return vo;
    }

}
