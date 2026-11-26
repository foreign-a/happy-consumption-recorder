package com.happy.consumption.capture

import java.lang.ref.WeakReference

/** A late disconnect from an old instance must never clear a newer connection. */
internal class ServiceConnectionSlot<T : Any> {
    private var current: WeakReference<T>? = null
    @Synchronized fun connect(service: T) { current = WeakReference(service) }
    @Synchronized fun current(): T? = current?.get()
    @Synchronized fun disconnect(service: T): Boolean {
        if (current?.get() !== service) return false
        current = null
        return true
    }
}
