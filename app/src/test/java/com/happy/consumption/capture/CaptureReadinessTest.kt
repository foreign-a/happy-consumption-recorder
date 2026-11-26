package com.happy.consumption.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureReadinessTest {
    @Test fun fastInitializationStillWaitsForShadeDismissal() = runBlocking {
        withTimeout(5_000) {
            val initStarted = CompletableDeferred<Unit>()
            val dismissalStarted = CompletableDeferred<Unit>()
            val initDone = CompletableDeferred<String>()
            val dismissDone = CompletableDeferred<Unit>()
            val result = async {
                awaitCaptureReadiness(
                    { initStarted.complete(Unit); initDone.await() },
                    { dismissalStarted.complete(Unit); dismissDone.await() }
                )
            }
            initStarted.await(); dismissalStarted.await()
            initDone.complete("session")
            yield()
            assertFalse(result.isCompleted)
            dismissDone.complete(Unit)
            assertEquals("session", result.await())
        }
    }

    @Test fun slowInitializationDoesNotStartDismissalWaitLate() = runBlocking {
        withTimeout(5_000) {
            val initStarted = CompletableDeferred<Unit>()
            val dismissalStarted = CompletableDeferred<Unit>()
            val initDone = CompletableDeferred<String>()
            val dismissDone = CompletableDeferred<Unit>()
            val result = async {
                awaitCaptureReadiness(
                    { initStarted.complete(Unit); initDone.await() },
                    { dismissalStarted.complete(Unit); dismissDone.await() }
                )
            }
            initStarted.await(); dismissalStarted.await()
            dismissDone.complete(Unit)
            yield()
            assertFalse(result.isCompleted)
            initDone.complete("session")
            assertEquals("session", result.await())
        }
    }

    @Test fun cancellationStopsBothPrerequisites() = runBlocking {
        withTimeout(5_000) {
            val initStarted = CompletableDeferred<Unit>()
            val dismissalStarted = CompletableDeferred<Unit>()
            var initClosed = false
            var dismissalClosed = false
            val result = async {
                awaitCaptureReadiness(
                    { try { initStarted.complete(Unit); CompletableDeferred<String>().await() } finally { initClosed = true } },
                    { try { dismissalStarted.complete(Unit); CompletableDeferred<Unit>().await() } finally { dismissalClosed = true } }
                )
            }
            initStarted.await(); dismissalStarted.await()
            result.cancelAndJoin()
            assertTrue(initClosed)
            assertTrue(dismissalClosed)
        }
    }
}
