package org.ruoyi.fault.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.ruoyi.fault.evidence.entity.DiagnosisCaseEntity;
import org.ruoyi.fault.evidence.entity.DiagnosisEvidenceEntity;
import org.ruoyi.fault.evidence.service.EvidenceChainService;
import org.ruoyi.fault.evidence.support.EvidenceCanonicalJsonWriter;
import org.ruoyi.fault.evidence.support.EvidenceHashCalculator;
import org.ruoyi.fault.evidence.support.Sha256Hasher;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceChainVerificationTest {

    private final EvidenceCanonicalJsonWriter writer = new EvidenceCanonicalJsonWriter(new ObjectMapper());
    private final EvidenceHashCalculator calculator = new EvidenceHashCalculator(writer, new Sha256Hasher());

    @Test
    void verifiesACompleteTwoEvidenceChain() {
        Chain chain = newChain();

        assertTrue(EvidenceChainService.verifyChain(chain.diagnosisCase(), chain.evidence(), calculator).valid());
    }

    @Test
    void rejectsTamperedResultMissingEvidenceSequenceGapAndRootHash() {
        Chain tampered = newChain();
        tampered.evidence().get(1).setResultSummaryJson(writer.write(Map.of("fault", "changed")));
        assertFalse(EvidenceChainService.verifyChain(tampered.diagnosisCase(), tampered.evidence(), calculator).valid());

        Chain missing = newChain();
        missing.evidence().remove(0);
        assertFalse(EvidenceChainService.verifyChain(missing.diagnosisCase(), missing.evidence(), calculator).valid());

        Chain sequenceGap = newChain();
        sequenceGap.evidence().get(1).setEvidenceSeq(3);
        assertFalse(EvidenceChainService.verifyChain(sequenceGap.diagnosisCase(), sequenceGap.evidence(), calculator).valid());

        Chain badRoot = newChain();
        badRoot.diagnosisCase().setRootHash("0".repeat(64));
        assertFalse(EvidenceChainService.verifyChain(badRoot.diagnosisCase(), badRoot.evidence(), calculator).valid());
    }

    private Chain newChain() {
        DiagnosisCaseEntity diagnosisCase = new DiagnosisCaseEntity();
        diagnosisCase.setId(1L);
        diagnosisCase.setCaseCode("FD-1");
        diagnosisCase.setEvidenceCount(2);
        DiagnosisEvidenceEntity first = evidence(1, "", Map.of("fault", "F30005"));
        DiagnosisEvidenceEntity second = evidence(2, first.getCurrentHash(), Map.of("quality", "GOOD"));
        diagnosisCase.setRootHash(second.getCurrentHash());
        return new Chain(diagnosisCase, new ArrayList<>(List.of(first, second)));
    }

    private DiagnosisEvidenceEntity evidence(int sequence, String previousHash, Object result) {
        LocalDateTime collectedAt = LocalDateTime.of(2026, 7, 26, 9, sequence);
        String code = String.format("EV-%03d", sequence);
        EvidenceHashCalculator.EvidenceHashes hashes = calculator.calculate("FD-1", sequence, code, "FAULT_CODE",
            "knowledge", "doc-1", Map.of("code", "F30005"), result, null, previousHash, collectedAt);
        DiagnosisEvidenceEntity evidence = new DiagnosisEvidenceEntity();
        evidence.setEvidenceSeq(sequence);
        evidence.setEvidenceCode(code);
        evidence.setEvidenceType("FAULT_CODE");
        evidence.setSourceSystem("knowledge");
        evidence.setSourceReference("doc-1");
        evidence.setRequestJson(hashes.requestCanonicalJson());
        evidence.setResultSummaryJson(hashes.resultCanonicalJson());
        evidence.setRequestHash(hashes.requestHash());
        evidence.setResultHash(hashes.resultHash());
        evidence.setPreviousHash(previousHash);
        evidence.setCurrentHash(hashes.currentHash());
        evidence.setHashVersion(EvidenceHashCalculator.HASH_VERSION);
        evidence.setCollectedAt(collectedAt);
        return evidence;
    }

    private record Chain(DiagnosisCaseEntity diagnosisCase, List<DiagnosisEvidenceEntity> evidence) {
    }
}
