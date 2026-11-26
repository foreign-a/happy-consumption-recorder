package com.happy.consumption.capture

import java.util.concurrent.Executors

internal interface PreparedOcrEngine<Image, Output> {
    fun recognize(image: Image): Output
    fun close()
}

/** One request, one thread, and no native engine or image retained after the request. */
internal class PreparedOcrWorker<Image, Output>(factory: (() -> Boolean) -> PreparedOcrEngine<Image, Output>) {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var submitted = false
    // The following two fields are accessed only on the executor thread.
    private var engine: PreparedOcrEngine<Image, Output>? = null
    private var failure: Throwable? = null

    init {
        executor.execute {
            try { if (!closed) engine = factory { closed } } catch (error: Throwable) { failure = error }
        }
    }

    fun submit(image: Image, deliver: (Result<Output>) -> Unit) = synchronized(lock) {
        check(!closed && !submitted)
        submitted = true
        executor.execute {
            if (synchronized(lock) { closed }) return@execute
            var result = runCatching {
                failure?.let { throw it }
                checkNotNull(engine).recognize(image)
            }
            try { engine?.close() } catch (error: Throwable) { result = Result.failure(error) }
            engine = null
            synchronized(lock) { if (!closed) deliver(result) }
        }
    }

    /** Disposal runs after any in-flight native read, on its owning thread. */
    fun close(dispose: () -> Unit = {}) = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        executor.execute {
            try { engine?.close() } finally { engine = null; dispose() }
        }
        executor.shutdown()
    }
}
