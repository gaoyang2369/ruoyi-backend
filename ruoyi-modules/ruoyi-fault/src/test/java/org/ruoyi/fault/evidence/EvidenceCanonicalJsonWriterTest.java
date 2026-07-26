package org.ruoyi.fault.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.ruoyi.fault.evidence.support.EvidenceCanonicalJsonWriter;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EvidenceCanonicalJsonWriterTest {

    private final EvidenceCanonicalJsonWriter writer = new EvidenceCanonicalJsonWriter(new ObjectMapper());

    @Test
    void writesEquivalentMapsAndObjectsStably() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", 1);
        first.put("a", 2);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", 2);
        second.put("z", 1);

        assertEquals(writer.write(first), writer.write(second));
        assertEquals("{\"a\":\"first\",\"z\":\"last\"}", writer.write(new UnorderedObject()));
    }

    @Test
    void writesTimesAndNullWithoutUsingToString() {
        assertEquals("\"2026-07-26T09:30:00\"", writer.write(LocalDateTime.of(2026, 7, 26, 9, 30)));
        assertEquals("null", writer.write(null));
        assertFalse(writer.write(new ToStringForbiddenObject()).contains("forbidden"));
    }

    private static final class UnorderedObject {
        public String getZ() {
            return "last";
        }

        public String getA() {
            return "first";
        }
    }

    private static final class ToStringForbiddenObject {
        public String getValue() {
            return "safe";
        }

        @Override
        public String toString() {
            throw new AssertionError("JSON writer must not call toString");
        }
    }
}
