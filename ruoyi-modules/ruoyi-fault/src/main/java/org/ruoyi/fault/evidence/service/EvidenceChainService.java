package org.ruoyi.fault.evidence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.fault.evidence.entity.DiagnosisCaseEntity;
import org.ruoyi.fault.evidence.entity.DiagnosisEvidenceEntity;
import org.ruoyi.fault.evidence.entity.DiagnosisStepEntity;
import org.ruoyi.fault.evidence.mapper.DiagnosisCaseMapper;
import org.ruoyi.fault.evidence.mapper.DiagnosisEvidenceMapper;
import org.ruoyi.fault.evidence.mapper.DiagnosisStepMapper;
import org.ruoyi.fault.evidence.model.EvidenceAppendCommand;
import org.ruoyi.fault.evidence.model.EvidenceAppendResult;
import org.ruoyi.fault.evidence.model.EvidenceChainVerificationResult;
import org.ruoyi.fault.evidence.support.EvidenceHashCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** 证据追加、哈希链维护和完整性校验服务。 */
@Service
@RequiredArgsConstructor
public class EvidenceChainService {

    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

    private final DiagnosisCaseMapper diagnosisCaseMapper;
    private final DiagnosisStepMapper diagnosisStepMapper;
    private final DiagnosisEvidenceMapper diagnosisEvidenceMapper;
    private final EvidenceHashCalculator evidenceHashCalculator;

    @Transactional(rollbackFor = Exception.class)
    public EvidenceAppendResult append(EvidenceAppendCommand command) {
        validateAppendCommand(command);
        DiagnosisCaseEntity diagnosisCase = diagnosisCaseMapper.selectByIdForUpdate(command.caseId());
        if (diagnosisCase == null) {
            throw new ServiceException("诊断案例不存在");
        }
        DiagnosisStepEntity step = diagnosisStepMapper.selectById(command.stepId());
        if (step == null) {
            throw new ServiceException("诊断步骤不存在");
        }
        if (!Objects.equals(step.getCaseId(), diagnosisCase.getId())) {
            throw new ServiceException("诊断步骤不属于该案例");
        }

        int nextSequence = diagnosisCase.getEvidenceCount() + 1;
        String evidenceCode = String.format(Locale.ROOT, "EV-%03d", nextSequence);
        String previousHash = diagnosisCase.getRootHash() == null ? "" : diagnosisCase.getRootHash();
        LocalDateTime collectedAt = LocalDateTime.now();
        String sourceSystem = command.sourceSystem().trim();
        EvidenceHashCalculator.EvidenceHashes hashes = evidenceHashCalculator.calculate(
            diagnosisCase.getCaseCode(), nextSequence, evidenceCode, command.evidenceType().name(), sourceSystem,
            command.sourceReference(), command.requestPayload(), command.resultSummary(), command.sourceDigest(),
            previousHash, collectedAt);

        DiagnosisEvidenceEntity evidence = new DiagnosisEvidenceEntity();
        evidence.setCaseId(diagnosisCase.getId());
        evidence.setStepId(step.getId());
        evidence.setEvidenceSeq(nextSequence);
        evidence.setEvidenceCode(evidenceCode);
        evidence.setEvidenceType(command.evidenceType().name());
        evidence.setSourceSystem(sourceSystem);
        evidence.setSourceReference(command.sourceReference());
        evidence.setRequestJson(hashes.requestCanonicalJson());
        evidence.setResultSummaryJson(hashes.resultCanonicalJson());
        evidence.setSourceRecordCount(command.sourceRecordCount());
        evidence.setSourceDigest(command.sourceDigest());
        evidence.setRequestHash(hashes.requestHash());
        evidence.setResultHash(hashes.resultHash());
        evidence.setPreviousHash(previousHash);
        evidence.setCurrentHash(hashes.currentHash());
        evidence.setHashVersion(EvidenceHashCalculator.HASH_VERSION);
        evidence.setQualityScore(command.qualityScore());
        evidence.setCollectedAt(collectedAt);
        if (diagnosisEvidenceMapper.insert(evidence) != 1) {
            throw new ServiceException("追加诊断证据失败");
        }

        DiagnosisCaseEntity changes = new DiagnosisCaseEntity();
        changes.setId(diagnosisCase.getId());
        changes.setEvidenceCount(nextSequence);
        changes.setRootHash(hashes.currentHash());
        if (diagnosisCaseMapper.updateById(changes) != 1) {
            throw new ServiceException("更新诊断案例根哈希失败");
        }
        return new EvidenceAppendResult(evidence.getId(), evidenceCode, nextSequence, hashes.currentHash());
    }

    public EvidenceChainVerificationResult verify(Long caseId) {
        DiagnosisCaseEntity diagnosisCase = diagnosisCaseMapper.selectById(caseId);
        if (diagnosisCase == null) {
            throw new ServiceException("诊断案例不存在");
        }
        List<DiagnosisEvidenceEntity> evidenceList = diagnosisEvidenceMapper.selectList(
            Wrappers.<DiagnosisEvidenceEntity>lambdaQuery()
                .eq(DiagnosisEvidenceEntity::getCaseId, caseId)
                .orderByAsc(DiagnosisEvidenceEntity::getEvidenceSeq));
        return verifyChain(diagnosisCase, evidenceList, evidenceHashCalculator);
    }

    /** 纯校验逻辑，供单元测试和后续离线审计复用。 */
    public static EvidenceChainVerificationResult verifyChain(DiagnosisCaseEntity diagnosisCase,
                                                               List<DiagnosisEvidenceEntity> evidenceList,
                                                               EvidenceHashCalculator hashCalculator) {
        int expectedCount = diagnosisCase.getEvidenceCount() == null ? 0 : diagnosisCase.getEvidenceCount();
        int actualCount = evidenceList.size();
        if (actualCount != expectedCount) {
            return invalid(diagnosisCase.getId(), expectedCount, actualCount, null, "evidence count mismatch");
        }
        String previousCurrentHash = "";
        for (int index = 0; index < evidenceList.size(); index++) {
            DiagnosisEvidenceEntity evidence = evidenceList.get(index);
            int expectedSequence = index + 1;
            if (!Objects.equals(evidence.getEvidenceSeq(), expectedSequence)) {
                return invalid(diagnosisCase.getId(), expectedCount, actualCount, evidence.getEvidenceSeq(),
                    "evidence sequence mismatch");
            }
            if (!Objects.equals(evidence.getEvidenceCode(), String.format(Locale.ROOT, "EV-%03d", expectedSequence))) {
                return invalid(diagnosisCase.getId(), expectedCount, actualCount, evidence.getEvidenceSeq(),
                    "evidence code mismatch");
            }
            if (!Objects.equals(evidence.getPreviousHash(), previousCurrentHash)) {
                return invalid(diagnosisCase.getId(), expectedCount, actualCount, evidence.getEvidenceSeq(),
                    "previous hash mismatch");
            }
            if (evidence.getRequestJson() == null
                || !Objects.equals(hashCalculator.hashCanonicalJson(evidence.getRequestJson()), evidence.getRequestHash())) {
                return invalid(diagnosisCase.getId(), expectedCount, actualCount, evidence.getEvidenceSeq(),
                    "request hash mismatch");
            }
            if (evidence.getResultSummaryJson() == null
                || !Objects.equals(hashCalculator.hashCanonicalJson(evidence.getResultSummaryJson()), evidence.getResultHash())) {
                return invalid(diagnosisCase.getId(), expectedCount, actualCount, evidence.getEvidenceSeq(),
                    "result hash mismatch");
            }
            String recalculatedCurrentHash = hashCalculator.calculateCurrentHash(evidence.getHashVersion(),
                diagnosisCase.getCaseCode(), evidence.getEvidenceSeq(), evidence.getEvidenceCode(), evidence.getEvidenceType(),
                evidence.getSourceSystem(), evidence.getSourceReference(), evidence.getRequestHash(), evidence.getResultHash(),
                evidence.getSourceDigest(), evidence.getPreviousHash(), evidence.getCollectedAt());
            if (!Objects.equals(recalculatedCurrentHash, evidence.getCurrentHash())) {
                return invalid(diagnosisCase.getId(), expectedCount, actualCount, evidence.getEvidenceSeq(),
                    "current hash mismatch");
            }
            previousCurrentHash = evidence.getCurrentHash();
        }
        if (evidenceList.isEmpty() && diagnosisCase.getRootHash() == null) {
            return new EvidenceChainVerificationResult(true, diagnosisCase.getId(), expectedCount, actualCount, null, null);
        }
        if (!Objects.equals(diagnosisCase.getRootHash(), previousCurrentHash)) {
            return invalid(diagnosisCase.getId(), expectedCount, actualCount, null, "root hash mismatch");
        }
        return new EvidenceChainVerificationResult(true, diagnosisCase.getId(), expectedCount, actualCount, null, null);
    }

    private static EvidenceChainVerificationResult invalid(Long caseId, int expectedCount, int actualCount,
                                                            Integer failedSequence, String reason) {
        return new EvidenceChainVerificationResult(false, caseId, expectedCount, actualCount, failedSequence, reason);
    }

    private static void validateAppendCommand(EvidenceAppendCommand command) {
        if (command == null || command.caseId() == null) {
            throw new ServiceException("诊断案例不能为空");
        }
        if (command.stepId() == null) {
            throw new ServiceException("诊断步骤不能为空");
        }
        if (command.evidenceType() == null) {
            throw new ServiceException("证据类型不能为空");
        }
        if (!StringUtils.hasText(command.sourceSystem())) {
            throw new ServiceException("证据来源系统不能为空");
        }
        if (command.resultSummary() == null) {
            throw new ServiceException("证据结果摘要不能为空");
        }
        if (command.sourceRecordCount() != null && command.sourceRecordCount() < 0) {
            throw new ServiceException("来源记录数不能小于0");
        }
        if (command.qualityScore() != null && (command.qualityScore().compareTo(BigDecimal.ZERO) < 0
            || command.qualityScore().compareTo(BigDecimal.ONE) > 0)) {
            throw new ServiceException("证据质量分数必须在0到1之间");
        }
        if (command.sourceDigest() != null && !SHA256_PATTERN.matcher(command.sourceDigest()).matches()) {
            throw new ServiceException("来源摘要必须是64位SHA-256");
        }
    }
}
