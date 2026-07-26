package org.ruoyi.fault.evidence.service;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.fault.evidence.entity.DiagnosisStepEntity;
import org.ruoyi.fault.evidence.enums.DiagnosisStepStatus;
import org.ruoyi.fault.evidence.mapper.DiagnosisCaseMapper;
import org.ruoyi.fault.evidence.mapper.DiagnosisStepMapper;
import org.ruoyi.fault.evidence.model.DiagnosisStepStartCommand;
import org.ruoyi.fault.evidence.support.EvidenceCanonicalJsonWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 诊断步骤生命周期服务。 */
@Service
@RequiredArgsConstructor
public class DiagnosisStepService {

    private final DiagnosisCaseMapper diagnosisCaseMapper;
    private final DiagnosisStepMapper diagnosisStepMapper;
    private final EvidenceCanonicalJsonWriter canonicalJsonWriter;

    @Transactional(rollbackFor = Exception.class)
    public DiagnosisStepEntity start(DiagnosisStepStartCommand command) {
        if (command == null || command.caseId() == null || diagnosisCaseMapper.selectById(command.caseId()) == null) {
            throw new ServiceException("诊断案例不存在");
        }
        if (command.stepNo() == null || command.stepNo() <= 0) {
            throw new ServiceException("诊断步骤序号无效");
        }
        if (command.stepType() == null) {
            throw new ServiceException("诊断步骤类型无效");
        }
        DiagnosisStepEntity entity = new DiagnosisStepEntity();
        entity.setCaseId(command.caseId());
        entity.setStepNo(command.stepNo());
        entity.setStepType(command.stepType().name());
        entity.setStatus(DiagnosisStepStatus.RUNNING.name());
        entity.setInputJson(canonicalJsonWriter.write(command.input()));
        entity.setStartedAt(LocalDateTime.now());
        if (diagnosisStepMapper.insert(entity) != 1) {
            throw new ServiceException("创建诊断步骤失败");
        }
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public void succeed(Long stepId, Object outputSummary) {
        requireStep(stepId);
        DiagnosisStepEntity changes = new DiagnosisStepEntity();
        changes.setId(stepId);
        changes.setStatus(DiagnosisStepStatus.SUCCEEDED.name());
        changes.setOutputSummaryJson(canonicalJsonWriter.write(outputSummary));
        changes.setFinishedAt(LocalDateTime.now());
        if (diagnosisStepMapper.updateById(changes) != 1) {
            throw new ServiceException("更新诊断步骤失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void fail(Long stepId, Throwable throwable) {
        requireStep(stepId);
        DiagnosisStepEntity changes = new DiagnosisStepEntity();
        changes.setId(stepId);
        changes.setStatus(DiagnosisStepStatus.FAILED.name());
        changes.setErrorMessage(DiagnosisCaseService.sanitizeErrorMessage(throwable == null ? null : throwable.getMessage()));
        changes.setFinishedAt(LocalDateTime.now());
        if (diagnosisStepMapper.updateById(changes) != 1) {
            throw new ServiceException("更新诊断步骤失败");
        }
    }

    private void requireStep(Long stepId) {
        if (stepId == null || diagnosisStepMapper.selectById(stepId) == null) {
            throw new ServiceException("诊断步骤不存在");
        }
    }
}
