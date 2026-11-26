package com.happy.consumption.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureRequestStateTest {
    @Test fun latestTriggerWinsEvenWhenIntentsArriveOutOfOrder() {
        val triggers = LatestCaptureTrigger()
        triggers.register("old")
        triggers.register("new")
        assertFalse(triggers.claim("old"))
        assertTrue(triggers.claim("new"))
        assertFalse(triggers.claim("new"))
        val restored = LatestCaptureTrigger()
        assertTrue(restored.claim("restored"))
        assertFalse(restored.claim("restored"))
    }
    @Test fun staleDuplicateAndExpiredRequestsCannotConsumeNewFrames() {
        val state = CaptureRequestState()
        assertFalse(state.hasCurrent())
        state.begin("first")
        assertTrue(state.hasCurrent())
        state.begin("second")
        assertFalse(state.consume("first"))
        assertTrue(state.isCurrent("second"))
        assertFalse(state.consume(null))
        assertTrue(state.consume("second"))
        assertFalse(state.hasCurrent())
        assertFalse(state.consume("second"))
        state.begin("third")
        assertFalse(state.consume("second"))
        assertTrue(state.isCurrent("third"))
        state.clear()
        assertFalse(state.isCurrent("third"))
    }
}
