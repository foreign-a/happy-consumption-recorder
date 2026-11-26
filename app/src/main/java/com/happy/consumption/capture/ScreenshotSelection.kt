package com.happy.consumption.capture

import com.happy.consumption.data.UserSession
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-private one-shot handoff; external intents carry no accounting data. */
object ScreenshotSelection {
    data class Pending(val id: String, val amountText: String, val session: UserSession, val processId: String)
    val processId: String = UUID.randomUUID().toString()
    private val queue = ScreenshotSelectionQueue(processId)
    val changes: StateFlow<Long> get() = queue.changes
    internal fun enqueue(amountText: String, session: UserSession) = queue.enqueue(amountText, session)
    fun claim(owner: String): Pending? = queue.claim(owner)
    fun restore(owner: String, id: String): Pending? = queue.restore(owner, id)
    fun complete(owner: String, id: String): Boolean = queue.complete(owner, id)
    fun abandon(owner: String) = queue.abandon(owner)
}

/** Each request has one owner, while separate app instances may hold separate drafts. */
internal class ScreenshotSelectionQueue(private val processId: String) {
    private val pending = ArrayDeque<ScreenshotSelection.Pending>()
    private val held = mutableMapOf<String, ScreenshotSelection.Pending>()
    private val changeState = MutableStateFlow(0L)
    val changes: StateFlow<Long> = changeState.asStateFlow()
    private fun changed() { changeState.value = changeState.value + 1 }

    @Synchronized fun enqueue(amountText: String, session: UserSession) {
        pending.addLast(ScreenshotSelection.Pending(UUID.randomUUID().toString(), amountText, session, processId))
        changed()
    }
    @Synchronized fun claim(owner: String): ScreenshotSelection.Pending? {
        held[owner]?.let { return it }
        val next = pending.pollFirst() ?: return null
        held[owner] = next
        return next
    }
    @Synchronized fun restore(owner: String, id: String): ScreenshotSelection.Pending? {
        return held[owner]?.takeIf { it.id == id }
    }
    @Synchronized fun complete(owner: String, id: String): Boolean {
        if (held[owner]?.id != id) return false
        held.remove(owner)
        changed()
        return true
    }
    @Synchronized fun abandon(owner: String) {
        if (held.remove(owner) == null) return
        changed()
    }
}
