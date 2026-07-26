package org.ruoyi.fault.evidence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.fault.evidence.entity.ConclusionEvidenceRelationEntity;
import org.ruoyi.fault.evidence.entity.DiagnosisEvidenceEntity;
import org.ruoyi.fault.evidence.mapper.ConclusionEvidenceRelationMapper;
import org.ruoyi.fault.evidence.mapper.DiagnosisCaseMapper;
import org.ruoyi.fault.evidence.mapper.DiagnosisEvidenceMapper;
import org.ruoyi.fault.evidence.model.ConclusionEvidenceLinkCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Objects;

/** 最小结论证据关联服务。 */
@Service
@RequiredArgsConstructor
public class ConclusionEvidenceRelationService {

    private final DiagnosisCaseMapper diagnosisCaseMapper;
    private final DiagnosisEvidenceMapper diagnosisEvidenceMapper;
    private final ConclusionEvidenceRelationMapper relationMapper;

    @Transactional(rollbackFor = Exception.class)
    public void link(ConclusionEvidenceLinkCommand command) {
        validate(command);
        if (diagnosisCaseMapper.selectById(command.caseId()) == null) {
            throw new ServiceException("诊断案例不存在");
        }
        DiagnosisEvidenceEntity evidence = diagnosisEvidenceMapper.selectById(command.evidenceId());
        if (evidence == null) {
            throw new ServiceException("诊断证据不存在");
        }
        if (!Objects.equals(evidence.getCaseId(), command.caseId())) {
            throw new ServiceException("诊断证据不属于该案例");
        }
        ConclusionEvidenceRelationEntity existing = relationMapper.selectOne(
            Wrappers.<ConclusionEvidenceRelationEntity>lambdaQuery()
                .eq(ConclusionEvidenceRelationEntity::getCaseId, command.caseId())
                .eq(ConclusionEvidenceRelationEntity::getConclusionCode, command.conclusionCode().trim())
                .eq(ConclusionEvidenceRelationEntity::getEvidenceId, command.evidenceId())
                .eq(ConclusionEvidenceRelationEntity::getRelationType, command.relationType().name()));
        if (existing != null) {
            return;
        }
        ConclusionEvidenceRelationEntity relation = new ConclusionEvidenceRelationEntity();
        relation.setCaseId(command.caseId());
        relation.setConclusionCode(command.conclusionCode().trim());
        relation.setEvidenceId(command.evidenceId());
        relation.setRelationType(command.relationType().name());
        relation.setWeight(command.weight());
        if (relationMapper.insert(relation) != 1) {
            throw new ServiceException("创建结论证据关联失败");
        }
    }

    private static void validate(ConclusionEvidenceLinkCommand command) {
        if (command == null || command.caseId() == null) {
            throw new ServiceException("诊断案例不能为空");
        }
        if (!StringUtils.hasText(command.conclusionCode())) {
            throw new ServiceException("结论编码不能为空");
        }
        if (command.evidenceId() == null) {
            throw new ServiceException("诊断证据不能为空");
        }
        if (command.relationType() == null) {
            throw new ServiceException("证据关系类型不能为空");
        }
        if (command.weight() != null && (command.weight().compareTo(BigDecimal.ZERO) < 0
            || command.weight().compareTo(BigDecimal.ONE) > 0)) {
            throw new ServiceException("证据权重必须在0到1之间");
        }
    }
}
