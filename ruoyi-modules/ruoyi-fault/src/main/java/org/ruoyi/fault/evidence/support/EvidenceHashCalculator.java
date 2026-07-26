package org.ruoyi.fault.evidence.support;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 计算证据请求、结果和串行链哈希。 */
@Component
public class EvidenceHashCalculator {

    public static final String HASH_VERSION = "SHA256_V1";

    private final EvidenceCanonicalJsonWriter canonicalJsonWriter;
    private final Sha256Hasher sha256Hasher;

    public EvidenceHashCalculator(EvidenceCanonicalJsonWriter canonicalJsonWriter, Sha256Hasher sha256Hasher) {
        this.canonicalJsonWriter = canonicalJsonWriter;
        this.sha256Hasher = sha256Hasher;
    }

    public EvidenceHashes calculate(String caseCode, Integer evidenceSeq, String evidenceCode, String evidenceType,
                                    String sourceSystem, String sourceReference, Object requestPayload,
                                    Object resultSummary, String sourceDigest, String previousHash,
                                    LocalDateTime collectedAt) {
        String requestCanonicalJson = canonicalJsonWriter.write(requestPayload);
        String resultCanonicalJson = canonicalJsonWriter.write(resultSummary);
        String requestHash = sha256Hasher.hash(requestCanonicalJson);
        String resultHash = sha256Hasher.hash(resultCanonicalJson);
        String currentHash = calculateCurrentHash(HASH_VERSION, caseCode, evidenceSeq, evidenceCode, evidenceType,
            sourceSystem, sourceReference, requestHash, resultHash, sourceDigest, previousHash, collectedAt);
        return new EvidenceHashes(requestCanonicalJson, resultCanonicalJson, requestHash, resultHash, currentHash);
    }

    public String hashCanonicalJson(String canonicalJson) {
        return sha256Hasher.hash(canonicalJson);
    }

    public String calculateCurrentHash(String hashVersion, String caseCode, Integer evidenceSeq, String evidenceCode,
                                       String evidenceType, String sourceSystem, String sourceReference,
                                       String requestHash, String resultHash, String sourceDigest, String previousHash,
                                       LocalDateTime collectedAt) {
        EvidenceHashPayloadV1 payload = new EvidenceHashPayloadV1(hashVersion, caseCode, evidenceSeq, evidenceCode,
            evidenceType, sourceSystem, sourceReference, requestHash, resultHash, sourceDigest, previousHash,
            collectedAt);
        return sha256Hasher.hash(canonicalJsonWriter.write(payload));
    }

    /** 已规范化的请求和结果 JSON 及其派生哈希。 */
    public record EvidenceHashes(String requestCanonicalJson, String resultCanonicalJson, String requestHash,
                                 String resultHash, String currentHash) {
    }

    private record EvidenceHashPayloadV1(
        String hashVersion,
        String caseCode,
        Integer evidenceSeq,
        String evidenceCode,
        String evidenceType,
        String sourceSystem,
        String sourceReference,
        String requestHash,
        String resultHash,
        String sourceDigest,
        String previousHash,
        LocalDateTime collectedAt
    ) {
    }
}
