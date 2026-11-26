package com.happy.consumption.capture

/** Main-thread ownership of the single pending capture. */
internal class CaptureRequestState {
    private var active: String? = null
    fun begin(id: String) { active = id }
    fun isCurrent(id: String?): Boolean = id != null && id == active
    fun hasCurrent(): Boolean = active != null
    fun consume(id: String?): Boolean {
        if (!isCurrent(id)) return false
        active = null
        return true
    }
    fun clear() { active = null }
}

/** Reject duplicated or reordered delivery of tile actions within the app process. */
internal class LatestCaptureTrigger {
    private var latest: String? = null
    private var claimed = false
    @Synchronized fun register(id: String) { latest = id; claimed = false }
    @Synchronized fun claim(id: String): Boolean {
        if (latest == null) latest = id // The process may have restarted after the system retained an intent.
        if (latest != id || claimed) return false
        claimed = true
        return true
    }
}
