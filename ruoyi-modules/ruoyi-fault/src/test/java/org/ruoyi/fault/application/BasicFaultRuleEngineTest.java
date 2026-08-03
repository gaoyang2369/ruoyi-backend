package org.ruoyi.fault.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.result.DiagnosisDecision;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class BasicFaultRuleEngineTest {

    private final BasicFaultRuleEngine engine = new BasicFaultRuleEngine();

    @Test
    void dataInsufficientWinsEvenWhenFaultCodeExists() {
        DiagnosisDecision decision = engine.evaluate(telemetry(false, List.of("F001"), List.of("A001")), List.of());

        assertEquals(DiagnosisStatus.DATA_INSUFFICIENT, decision.status());
        assertEquals(List.of("FAULT_CODE:F001", "ALARM_CODE:A001"), decision.observations().stream()
            .filter(observation -> observation.observationCode().startsWith("FAULT")
                || observation.observationCode().startsWith("ALARM"))
            .map(observation -> observation.observationCode()).toList());
    }

    @Test
    void selectsFaultThenWarningThenNoExplicitFaultInPriorityOrder() {
        assertEquals(DiagnosisStatus.FAULT_DETECTED,
            engine.evaluate(telemetry(true, List.of("F001"), List.of("A001")), List.of()).status());
        assertEquals(DiagnosisStatus.WARNING_DETECTED,
            engine.evaluate(telemetry(true, List.of(), List.of("A001")), List.of()).status());

        DiagnosisDecision noExplicitFault = engine.evaluate(telemetry(true, List.of(), List.of()), List.of());
        assertEquals(DiagnosisStatus.NO_EXPLICIT_FAULT, noExplicitFault.status());
        assertTrue(noExplicitFault.limitations().stream().anyMatch(value -> value.contains("不代表设备完全健康")));
        assertTrue(noExplicitFault.limitations().stream().anyMatch(value -> value.contains("尚未接入异常检测模型")));
    }

    @Test
    void producesStableRecommendationAndLimitationOrder() {
        DiagnosisDecision first = engine.evaluate(telemetry(true, List.of(), List.of()), List.of());
        DiagnosisDecision second = engine.evaluate(telemetry(true, List.of(), List.of()), List.of());

        assertEquals(first.recommendations(), second.recommendations());
        assertEquals(first.limitations(), second.limitations());
    }

    private static TelemetryQueryResult telemetry(boolean sufficient, List<String> faultCodes, List<String> alarmCodes) {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new TelemetryQueryResult("device", start, start.plusMinutes(5),
            new DataQualitySummary(1, 1, 0, 0, 0, 1D, sufficient), faultCodes, alarmCodes, List.of(), List.of(),
            null, null, false, start.plusMinutes(4), List.of());
    }
}
