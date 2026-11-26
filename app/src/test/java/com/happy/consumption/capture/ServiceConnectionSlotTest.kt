package com.happy.consumption.capture

import org.junit.Assert.*
import org.junit.Test

class ServiceConnectionSlotTest {
    @Test fun lateDisconnectCannotClearNewConnection() {
        val slot = ServiceConnectionSlot<Any>()
        val old = Any()
        val current = Any()
        slot.connect(old)
        slot.connect(current)
        assertFalse(slot.disconnect(old))
        assertSame(current, slot.current())
        assertTrue(slot.disconnect(current))
        assertNull(slot.current())
        assertFalse(slot.disconnect(current))
    }

    @Test fun duplicateConnectAndDisconnectAreSafe() {
        val slot = ServiceConnectionSlot<Any>()
        val current = Any()
        assertNull(slot.current())
        slot.connect(current)
        slot.connect(current)
        assertSame(current, slot.current())
        assertTrue(slot.disconnect(current))
        assertNull(slot.current())
    }
}
