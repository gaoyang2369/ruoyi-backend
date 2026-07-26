package org.ruoyi.fault.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 精确故障码 token 匹配测试。 */
class FaultCodeExactMatcherTest {

    @Test
    void matchesExactFaultCodeButRejectsAdjacentCode() {
        assertTrue(FaultCodeExactMatcher.matches("故障 F30005：功率单元过载", "F30005"));
        assertTrue(FaultCodeExactMatcher.matches("f30005", "F30005"));
        assertFalse(FaultCodeExactMatcher.matches("F30005A：另一故障", "F30005"));
        assertFalse(FaultCodeExactMatcher.matches("XF30005：另一故障", "F30005"));
    }
}
