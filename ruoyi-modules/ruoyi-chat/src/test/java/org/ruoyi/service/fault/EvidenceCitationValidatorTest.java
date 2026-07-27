package org.ruoyi.service.fault;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceCitationValidatorTest {
    private final EvidenceCitationValidator validator = new EvidenceCitationValidator();

    @Test void acceptsAllRealEvidenceCodes() {
        assertTrue(validator.valid("已观察到告警 [EV-12]，请检查 [EV-13]", Set.of("EV-12", "EV-13"), true));
    }
    @Test void rejectsUnknownOrUnexpectedEvidenceCodes() {
        assertFalse(validator.valid("引用 [EV-999]", Set.of("EV-12"), true));
        assertFalse(validator.valid("引用 [EV-12]", Set.of(), false));
    }
    @Test void requiresCitationOnlyWhenDiagnosisActuallyHasEvidence() {
        assertFalse(validator.valid("没有引用", Set.of("EV-12"), true));
        assertTrue(validator.valid("普通括号（EV-12）", Set.of("EV-12"), false));
    }
}
