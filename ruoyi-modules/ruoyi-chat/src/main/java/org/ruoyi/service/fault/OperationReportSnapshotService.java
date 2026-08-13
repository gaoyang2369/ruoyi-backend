package org.ruoyi.service.fault;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.domain.entity.fault.OperationReportSnapshot;
import org.ruoyi.fault.report.OperationReportResult;
import org.ruoyi.mapper.fault.OperationReportSnapshotMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 保存并按用户、租户读取运行报告快照。 */
@Service
@RequiredArgsConstructor
public class OperationReportSnapshotService {

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_PREPARED = "PREPARED";

    private final OperationReportSnapshotMapper mapper;
    private final ObjectMapper objectMapper;

    public OperationReportResult save(OperationReportResult report, Long sessionId, Long userId, String tenantId) {
        return save(report, sessionId, userId, tenantId, STATUS_COMPLETED);
    }

    /** 保存一次已经完成确定性分析、等待 Hermes 提交 narrative 的快照。 */
    public OperationReportResult prepare(OperationReportResult report, Long sessionId, Long userId, String tenantId) {
        return save(report, sessionId, userId, tenantId, STATUS_PREPARED);
    }

    private OperationReportResult save(OperationReportResult report, Long sessionId, Long userId, String tenantId,
                                       String status) {
        if (report == null || report.metadata() == null || userId == null || tenantId == null) {
            throw new ServiceException("运行报告保存上下文不完整");
        }
        OperationReportSnapshot entity = new OperationReportSnapshot();
        entity.setReportCode(report.metadata().reportId());
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setDeviceName(report.asset().deviceName());
        entity.setInverterName(report.asset().inverterName());
        entity.setWindowStart(report.period().windowStart());
        entity.setWindowEnd(report.period().windowEnd());
        entity.setReportStatus(status);
        entity.setPeriodStatus(report.periodStatus().name());
        entity.setCurrentStatus(report.currentStatus().name());
        try {
            entity.setReportJson(objectMapper.writeValueAsString(report));
            if (mapper.insert(entity) != 1) {
                throw new ServiceException("运行报告保存失败");
            }
        } catch (DuplicateKeyException ex) {
            throw new ServiceException("运行报告编号已存在");
        } catch (JsonProcessingException ex) {
            throw new ServiceException("运行报告序列化失败");
        }
        return report;
    }

    /**
     * 将已校验 narrative 写回同一 report snapshot。这里绝不调用遥测服务，保证 prepare/finalize 使用同一事实源。
     */
    public OperationReportResult finalize(String reportCode, OperationReportResult.ReportNarrative narrative,
                                          Long userId, String tenantId) {
        OperationReportSnapshot snapshot = snapshotOf(reportCode, userId, tenantId);
        OperationReportResult report = readReport(snapshot);
        OperationReportResult finalized = report.withNarrative(narrative);
        snapshot.setReportStatus(STATUS_COMPLETED);
        try {
            snapshot.setReportJson(objectMapper.writeValueAsString(finalized));
        } catch (JsonProcessingException ex) {
            throw new ServiceException("运行报告序列化失败");
        }
        if (mapper.updateById(snapshot) != 1) {
            throw new ServiceException("运行报告保存失败");
        }
        return finalized;
    }

    public OperationReportResult get(String reportCode, Long userId, String tenantId) {
        if (reportCode == null || reportCode.isBlank() || userId == null || tenantId == null) {
            throw new ServiceException("运行报告不存在或无权访问");
        }
        return readReport(snapshotOf(reportCode, userId, tenantId));
    }

    private OperationReportSnapshot snapshotOf(String reportCode, Long userId, String tenantId) {
        if (reportCode == null || reportCode.isBlank() || userId == null || tenantId == null) {
            throw new ServiceException("运行报告不存在或无权访问");
        }
        OperationReportSnapshot snapshot = mapper.selectOne(Wrappers.<OperationReportSnapshot>lambdaQuery()
            .eq(OperationReportSnapshot::getReportCode, reportCode.trim())
            .eq(OperationReportSnapshot::getUserId, userId)
            .eq(OperationReportSnapshot::getTenantId, tenantId));
        if (snapshot == null) throw new ServiceException("运行报告不存在或无权访问");
        return snapshot;
    }

    private OperationReportResult readReport(OperationReportSnapshot snapshot) {
        try {
            return objectMapper.readValue(snapshot.getReportJson(), OperationReportResult.class);
        } catch (JsonProcessingException ex) {
            throw new ServiceException("运行报告快照损坏");
        }
    }
}
