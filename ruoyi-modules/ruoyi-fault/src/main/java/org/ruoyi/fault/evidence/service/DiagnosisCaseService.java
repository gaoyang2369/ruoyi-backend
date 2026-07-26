package org.ruoyi.fault.evidence.service;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.fault.evidence.entity.DiagnosisCaseEntity;
import org.ruoyi.fault.evidence.enums.DiagnosisCaseStatus;
import org.ruoyi.fault.evidence.mapper.DiagnosisCaseMapper;
import org.ruoyi.fault.evidence.model.DiagnosisCaseCreateCommand;
import org.ruoyi.fault.evidence.support.EvidenceCanonicalJsonWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 诊断案例生命周期服务。 */
@Service
@RequiredArgsConstructor
public class DiagnosisCaseService {

    private final DiagnosisCaseMapper diagnosisCaseMapper;
    private final EvidenceCanonicalJsonWriter canonicalJsonWriter;

    @Transactional(rollbackFor = Exception.class)
    public DiagnosisCaseEntity create(DiagnosisCaseCreateCommand command) {
        if (command == null || !StringUtils.hasText(command.assetCode())) {
            throw new ServiceException("资产编码不能为空");
        }
        if (!StringUtils.hasText(command.question())) {
            throw new ServiceException("诊断问题不能为空");
        }
        if (command.queryStartTime() == null || command.queryEndTime() == null
            || !command.queryStartTime().isBefore(command.queryEndTime())) {
            throw new ServiceException("诊断时间范围无效");
        }
        DiagnosisCaseEntity entity = new DiagnosisCaseEntity();
        entity.setCaseCode("FD-" + IdUtil.getSnowflakeNextId());
        entity.setSessionId(command.sessionId());
        entity.setAgentId(command.agentId());
        entity.setUserId(command.userId());
        entity.setAssetCode(command.assetCode().trim());
        entity.setQuestion(command.question().trim());
        entity.setQueryStartTime(command.queryStartTime());
        entity.setQueryEndTime(command.queryEndTime());
        entity.setStatus(DiagnosisCaseStatus.CREATED.name());
        entity.setEvidenceCount(0);
        if (diagnosisCaseMapper.insert(entity) != 1) {
            throw new ServiceException("创建诊断案例失败");
        }
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRunning(Long caseId) {
        updateStatus(caseId, DiagnosisCaseStatus.RUNNING, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markSucceeded(Long caseId, Object conclusion) {
        updateStatus(caseId, DiagnosisCaseStatus.SUCCEEDED, canonicalJsonWriter.write(conclusion), null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markPartial(Long caseId, Object conclusion, String errorMessage) {
        updateStatus(caseId, DiagnosisCaseStatus.PARTIAL, canonicalJsonWriter.write(conclusion), sanitizeErrorMessage(errorMessage));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markFailed(Long caseId, String errorMessage) {
        updateStatus(caseId, DiagnosisCaseStatus.FAILED, null, sanitizeErrorMessage(errorMessage));
    }

    private void updateStatus(Long caseId, DiagnosisCaseStatus status, String conclusionJson, String errorMessage) {
        requireCase(caseId);
        DiagnosisCaseEntity changes = new DiagnosisCaseEntity();
        changes.setId(caseId);
        changes.setStatus(status.name());
        changes.setConclusionJson(conclusionJson);
        changes.setErrorMessage(errorMessage);
        if (diagnosisCaseMapper.updateById(changes) != 1) {
            throw new ServiceException("更新诊断案例失败");
        }
    }

    private void requireCase(Long caseId) {
        if (caseId == null || diagnosisCaseMapper.selectById(caseId) == null) {
            throw new ServiceException("诊断案例不存在");
        }
    }

    static String sanitizeErrorMessage(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String sanitized = value.replaceAll("(?i)(password|token|secret|authorization)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]")
            .replaceAll("[\\r\\n]+", " ").trim();
        return sanitized.length() > 2000 ? sanitized.substring(0, 2000) : sanitized;
    }
}
