package com.happy.consumption.capture

/** Main-thread generation and single-selection guard for replaced preview frames. */
internal class PreviewFrameGate {
    private var generation = 0L
    private var selected = false
    fun next(): Long { selected = false; return ++generation }
    fun isCurrent(value: Long): Boolean = value == generation
    fun trySelect(value: Long): Boolean {
        if (!isCurrent(value) || selected) return false
        selected = true
        return true
    }
    fun invalidate() { generation++ }
}
