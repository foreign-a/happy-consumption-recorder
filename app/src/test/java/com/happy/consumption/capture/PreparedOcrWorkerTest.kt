package com.happy.consumption.capture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PreparedOcrWorkerTest {
    private fun CountDownLatch.awaitDone() = assertTrue(await(5, TimeUnit.SECONDS))

    @Test fun prewarmsRecognizesAndReleasesExactlyOnce() {
        val ready = CountDownLatch(1); val delivered = CountDownLatch(1); val disposed = CountDownLatch(1)
        val closes = AtomicInteger(); val result = AtomicReference<String>()
        val worker = PreparedOcrWorker<Int, String> {
            ready.countDown()
            object : PreparedOcrEngine<Int, String> {
                override fun recognize(image: Int) = "result-$image"
                override fun close() { closes.incrementAndGet() }
            }
        }
        ready.awaitDone()
        worker.submit(7) { result.set(it.getOrThrow()); delivered.countDown() }
        delivered.awaitDone()
        worker.close { disposed.countDown() }
        disposed.awaitDone()
        assertEquals("result-7", result.get()); assertEquals(1, closes.get())
    }

    @Test fun cancellationDuringPreparationReleasesEngineAndDisposes() {
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1); val disposed = CountDownLatch(1)
        val closes = AtomicInteger(); val delivered = AtomicInteger()
        val worker = PreparedOcrWorker<Int, Int> {
            entered.countDown(); proceed.awaitDone()
            object : PreparedOcrEngine<Int, Int> {
                override fun recognize(image: Int) = image
                override fun close() { closes.incrementAndGet() }
            }
        }
        entered.awaitDone()
        worker.submit(1) { delivered.incrementAndGet() }
        worker.close { disposed.countDown() }
        proceed.countDown(); disposed.awaitDone()
        assertEquals(0, delivered.get()); assertEquals(1, closes.get())
    }

    @Test fun cancellationWaitsForInFlightReadBeforeDisposal() {
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1); val disposed = CountDownLatch(1)
        val closes = AtomicInteger(); val delivered = AtomicInteger()
        val worker = PreparedOcrWorker<Int, Int> {
            object : PreparedOcrEngine<Int, Int> {
                override fun recognize(image: Int): Int { entered.countDown(); proceed.awaitDone(); return image }
                override fun close() { closes.incrementAndGet() }
            }
        }
        worker.submit(1) { delivered.incrementAndGet() }; entered.awaitDone()
        worker.close { disposed.countDown() }
        assertEquals(1L, disposed.count)
        proceed.countDown(); disposed.awaitDone()
        assertEquals(0, delivered.get()); assertEquals(1, closes.get())
    }

    @Test fun preparationAndRecognitionFailuresAreDeliveredAndCleanedUp() {
        val preparedFailure = CountDownLatch(1); val done1 = CountDownLatch(1)
        val worker = PreparedOcrWorker<Int, Int> { error("prepare failed") }
        worker.submit(1) { assertTrue(it.isFailure); preparedFailure.countDown() }
        preparedFailure.awaitDone(); worker.close { done1.countDown() }; done1.awaitDone()
        val recognitionFailure = CountDownLatch(1); val done2 = CountDownLatch(1); val closes = AtomicInteger()
        val other = PreparedOcrWorker<Int, Int> {
            object : PreparedOcrEngine<Int, Int> {
                override fun recognize(image: Int): Int = error("recognize failed")
                override fun close() { closes.incrementAndGet() }
            }
        }
        other.submit(1) { assertTrue(it.isFailure); recognitionFailure.countDown() }
        recognitionFailure.awaitDone(); other.close { done2.countDown() }; done2.awaitDone()
        assertEquals(1, closes.get())
    }
}
