package com.happy.consumption.capture

import org.junit.Assert.*
import org.junit.Test

class PreviewFrameGateTest {
    @Test fun replacementRejectsOldCallbacksAndAllowsOnlyOneSelection() {
        val gate = PreviewFrameGate()
        val first = gate.next()
        assertTrue(gate.trySelect(first))
        assertFalse(gate.trySelect(first))
        val second = gate.next()
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.trySelect(first))
        assertTrue(gate.trySelect(second))
        assertFalse(gate.trySelect(second))
        gate.invalidate()
        assertFalse(gate.isCurrent(second))
        assertFalse(gate.trySelect(second))
    }
}
