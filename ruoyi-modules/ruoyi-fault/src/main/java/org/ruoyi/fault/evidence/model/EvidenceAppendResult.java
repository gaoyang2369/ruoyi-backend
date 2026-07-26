package org.ruoyi.fault.evidence.model;

/** 成功追加证据后的服务端生成值。 */
public record EvidenceAppendResult(Long evidenceId, String evidenceCode, Integer evidenceSeq, String currentHash) {
}
