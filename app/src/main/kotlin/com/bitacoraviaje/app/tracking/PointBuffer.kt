package com.bitacoraviaje.app.tracking

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pending items waiting to be written in one batch by [sink].
 *
 * Not thread-safe: every call must come from one single-threaded dispatcher (the service
 * pipeline). Only [flush] is serialized, with a mutex, because it suspends inside the sink
 * and the dispatcher keeps running other coroutines meanwhile: two flushes used to copy the
 * same head of the list and the second `subList(0, n).clear()` threw and killed the
 * recording process (evidence in `docs/notes/evidencia/backlog/F1-flush-race.md`). A late
 * caller now finds the list already drained and returns. Items are cleared only after the
 * sink succeeded, and the write cannot be cancelled by scope teardown (I2): a failed write
 * keeps the items for the next attempt.
 */
class PointBuffer<T>(private val sink: suspend (List<T>) -> Unit) {
    private val items = ArrayList<T>()
    private val lock = Mutex()

    /** Wall-clock time of the last successful flush, 0 before the first. */
    var lastFlushMs: Long = 0L
        private set

    val size: Int get() = items.size
    fun isNotEmpty(): Boolean = items.isNotEmpty()

    fun add(item: T) {
        items += item
    }

    suspend fun flush(nowMs: Long = System.currentTimeMillis()) = lock.withLock {
        if (items.isEmpty()) return@withLock
        val batch = items.toList()
        withContext(NonCancellable) { sink(batch) }
        // Only this flush removes items and callers only append: the head is still exactly [batch].
        items.subList(0, batch.size).clear()
        lastFlushMs = nowMs
    }
}
