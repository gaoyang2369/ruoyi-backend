package org.ruoyi.fault.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.ruoyi.fault.evidence.support.EvidenceCanonicalJsonWriter;
import org.ruoyi.fault.evidence.support.EvidenceHashCalculator;
import org.ruoyi.fault.evidence.support.Sha256Hasher;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@Tag("dev")
class EvidenceHashCalculatorTest {

    private final EvidenceHashCalculator calculator = new EvidenceHashCalculator(
        new EvidenceCanonicalJsonWriter(new ObjectMapper()), new Sha256Hasher());
    private final LocalDateTime collectedAt = LocalDateTime.of(2026, 7, 26, 9, 30);

    @Test
    void calculatesAStableHashThatCoversResultSequenceAndPreviousHash() {
        EvidenceHashCalculator.EvidenceHashes original = calculate(1, "", Map.of("fault", "F30005"));

        assertEquals(original, calculate(1, "", Map.of("fault", "F30005")));
        assertNotEquals(original.currentHash(), calculate(1, "", Map.of("fault", "F30006")).currentHash());
        assertNotEquals(original.currentHash(), calculate(2, "", Map.of("fault", "F30005")).currentHash());
        assertNotEquals(original.currentHash(), calculate(1, "previous-hash", Map.of("fault", "F30005")).currentHash());
    }

    private EvidenceHashCalculator.EvidenceHashes calculate(int sequence, String previousHash, Object result) {
        return calculator.calculate("FD-1", sequence, String.format("EV-%03d", sequence), "FAULT_CODE", "knowledge",
            "doc-1", Map.of("code", "F30005"), result, null, previousHash, collectedAt);
    }
}
