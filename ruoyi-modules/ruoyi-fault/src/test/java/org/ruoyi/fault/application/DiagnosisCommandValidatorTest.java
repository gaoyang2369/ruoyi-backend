package org.ruoyi.fault.application;

import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.ruoyi.fault.domain.context.DiagnosisRequestContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiagnosisCommandValidatorTest {

    private final DiagnosisCommandValidator validator = new DiagnosisCommandValidator();
    private final LocalDateTime start = LocalDateTime.of(2026, 1, 1, 10, 0);
    private final LocalDateTime end = start.plusMinutes(10);

    @Test
    void normalizesStringsAndKnowledgeBaseIdsInFirstSeenOrder() {
        DiagnosisCommand result = validator.validateAndNormalize(command(" device ", " inverter ", start, end,
            " symptom ", List.of(3L, 1L, 3L)));

        assertEquals("device", result.deviceName());
        assertEquals("inverter", result.inverterName());
        assertEquals("symptom", result.symptom());
        assertEquals(List.of(3L, 1L), result.knowledgeBaseIds());
        assertThrows(UnsupportedOperationException.class, () -> result.knowledgeBaseIds().add(4L));
    }

    @Test
    void normalizesNullOptionalFields() {
        DiagnosisCommand result = validator.validateAndNormalize(command("device", "inverter", start, end,
            null, null));

        assertNull(result.symptom());
        assertEquals(List.of(), result.knowledgeBaseIds());
    }

    @Test
    void rejectsRequiredValuesAndInvalidWindow() {
        assertInvalid(command(" ", "inverter", start, end, null, List.of()));
        assertInvalid(command("device", " ", start, end, null, List.of()));
        assertInvalid(command("device", "inverter", null, end, null, List.of()));
        assertInvalid(command("device", "inverter", start, null, null, List.of()));
        assertInvalid(command("device", "inverter", start, start, null, List.of()));
        assertInvalid(command("device", "inverter", end, start, null, List.of()));
        assertInvalid(new DiagnosisCommand("device", "inverter", start, end, null, List.of(), null));
    }

    @Test
    void rejectsNullAndNonPositiveKnowledgeBaseIds() {
        List<Long> nullId = new ArrayList<>();
        nullId.add(null);
        assertInvalid(command("device", "inverter", start, end, null, nullId));
        assertInvalid(command("device", "inverter", start, end, null, List.of(0L)));
        assertInvalid(command("device", "inverter", start, end, null, List.of(-1L)));
    }

    private void assertInvalid(DiagnosisCommand command) {
        assertThrows(ServiceException.class, () -> validator.validateAndNormalize(command));
    }

    private DiagnosisCommand command(String device, String inverter, LocalDateTime commandStart, LocalDateTime commandEnd,
                                     String symptom, List<Long> knowledgeBaseIds) {
        return new DiagnosisCommand(device, inverter, commandStart, commandEnd, symptom, knowledgeBaseIds,
            new DiagnosisRequestContext(1L, 2L, 3L, "tenant", "request"));
    }
}
