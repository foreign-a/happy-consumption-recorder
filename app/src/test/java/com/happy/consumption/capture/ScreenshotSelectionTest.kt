package com.happy.consumption.capture

import com.happy.consumption.data.UserSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ScreenshotSelectionTest {
    private val session = UserSession(12, "example", 4)

    @Test fun anotherInstanceCanClaimANewSelectionWithoutReplacingTheExistingDraft() {
        val queue = ScreenshotSelectionQueue("process")
        queue.enqueue("12.34", session)
        val first = requireNotNull(queue.claim("owner-a"))
        queue.enqueue("12.34", session)
        assertEquals(first, queue.claim("owner-a"))
        val second = requireNotNull(queue.claim("owner-b"))
        assertNull(queue.claim("owner-c"))
        assertNotEquals(first.id, second.id)
        assertEquals(first.amountText, second.amountText)
        assertFalse(queue.complete("owner-b", first.id))
        assertTrue(queue.complete("owner-a", first.id))
        assertEquals(second, queue.claim("owner-b"))
        assertTrue(queue.complete("owner-b", second.id))
        assertNull(queue.claim("owner-a"))
    }

    @Test fun originalSessionsAndGenerationsSurviveQueueingAndAbandonment() {
        val queue = ScreenshotSelectionQueue("process")
        val a = UserSession(1, "a", 1)
        val aRelogin = UserSession(1, "a", 2)
        val b = UserSession(2, "b", 1)
        for (s in listOf(a, aRelogin, b)) queue.enqueue("3", s)
        val first = requireNotNull(queue.claim("old-owner"))
        assertEquals(a, first.session)
        queue.abandon("old-owner")
        assertNull(queue.restore("old-owner", first.id))
        val second = requireNotNull(queue.claim("new-owner"))
        assertEquals(aRelogin, second.session)
        assertTrue(queue.complete("new-owner", second.id))
        assertEquals(b, requireNotNull(queue.claim("new-owner")).session)
    }

    @Test fun recreateRestoresOnlyTheSameOwnerAndRequest() {
        val queue = ScreenshotSelectionQueue("process")
        queue.enqueue("5.67", session)
        val selected = requireNotNull(queue.claim("owner"))
        assertNull(queue.restore("another-owner", selected.id))
        assertNull(queue.restore("owner", "stale"))
        assertEquals(selected, queue.restore("owner", selected.id))
        assertNull(queue.claim("another-owner"))
        assertNull(ScreenshotSelectionQueue("new-process").restore("owner", selected.id))
    }

    @Test fun changesNotifyForegroundObserversAndRetainLatestRevision() {
        val queue = ScreenshotSelectionQueue("process")
        val initial = queue.changes.value
        queue.enqueue("1", session)
        val afterEnqueue = queue.changes.value
        assertNotEquals(initial, afterEnqueue)
        val chosen = requireNotNull(queue.claim("owner"))
        queue.abandon("owner")
        assertNotEquals(afterEnqueue, queue.changes.value)
        assertNull(queue.claim("owner"))
        queue.enqueue("2", session)
        val next = requireNotNull(queue.claim("owner"))
        val beforeComplete = queue.changes.value
        assertFalse(queue.complete("owner", chosen.id))
        assertTrue(queue.complete("owner", next.id))
        assertNotEquals(beforeComplete, queue.changes.value)
    }

    @Test fun concurrentVisibleInstancesCannotBothClaimOneDraft() {
        val queue = ScreenshotSelectionQueue("process")
        queue.enqueue("4", session)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val a = AtomicReference<ScreenshotSelection.Pending?>()
        val b = AtomicReference<ScreenshotSelection.Pending?>()
        val threads = listOf("a" to a, "b" to b).map { (owner, result) ->
            Thread { start.await(); result.set(queue.claim(owner)); finished.countDown() }.apply { start() }
        }
        start.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        threads.forEach { it.join() }
        assertEquals(1, listOf(a.get(), b.get()).count { it != null })
    }
}
