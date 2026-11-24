package com.bitacoraviaje.app.tracking

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pending items waiting to be written in one batch by [sink].
 *
 * [flush] suspends inside the sink, and the single-thread pipeline keeps running other
 * coroutines meanwhile (the ticker, a pause, more fixes): two flushes used to interleave,
 * both copied the same head of the list and the second `subList(0, n).clear()` threw
 * `IndexOutOfBoundsException` and killed the recording process (seen 7 times on the
 * 2026-11-20 trip). A mutex makes the flush exclusive; a late caller finds the list
 * already drained and returns. Items are cleared only after the sink succeeded, and the
 * write cannot be cancelled by scope teardown (I2): a failed write keeps the items.
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
        // Only the pipeline appends, so the head of the list is still exactly [batch].
        items.subList(0, batch.size).clear()
        lastFlushMs = nowMs
    }
}
