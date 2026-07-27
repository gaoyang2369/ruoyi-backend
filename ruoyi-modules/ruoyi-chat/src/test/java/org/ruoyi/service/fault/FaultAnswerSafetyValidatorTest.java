package org.ruoyi.service.fault;

import org.junit.jupiter.api.Test;
import org.ruoyi.service.fault.model.FaultExecutionResult;
import org.ruoyi.service.fault.model.FaultRequestPlan;
import org.ruoyi.service.fault.model.FaultTaskType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaultAnswerSafetyValidatorTest {
    private final FaultAnswerSafetyValidator validator = new FaultAnswerSafetyValidator(new EvidenceCitationValidator());
    private final FaultExecutionResult queriedOnly = new FaultExecutionResult(
        new FaultRequestPlan(List.of(FaultTaskType.EXPLAIN_FAULT_CODE), null, null, null, null, null,
            List.of("F30005"), null, List.of()), null, Map.of(), List.of());

    @Test void rejectsQueriedOnlyCodeClaimedAsObserved() {
        assertFalse(validator.valid("本次检测到 F30005。", queriedOnly, false));
    }

    @Test void permitsExplicitNegationAndKnowledgeExplanation() {
        assertTrue(validator.valid("本次未检测到 F30005；这里仅说明其知识含义。", queriedOnly, false));
    }

    @Test void rejectsInventedFaultCodeAndConfidence() {
        assertFalse(validator.valid("F99999 的故障概率为 0.9", queriedOnly, false));
        assertFalse(validator.valid("F30005 的置信度 80%", queriedOnly, false));
    }
}
