package com.bitacoraviaje.app.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PointBufferTest {
    @Test
    fun `two overlapping flushes write every item once and never throw`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val written = mutableListOf<Int>()
        val buffer = PointBuffer<Int> { batch -> gate.await(); written += batch }
        repeat(5) { buffer.add(it) }

        val first = launch { buffer.flush(nowMs = 1) } // suspends inside the sink
        advanceUntilIdle()
        buffer.add(5) // a fix arrives while the write is in flight
        val second = launch { buffer.flush(nowMs = 2) } // the ticker flushes too
        advanceUntilIdle()
        assertTrue(second.isActive, "second flush must wait for the first")

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(first.isCompleted && second.isCompleted)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), written)
        assertFalse(buffer.isNotEmpty())
        assertEquals(2L, buffer.lastFlushMs)
    }

    @Test
    fun `a late flush with nothing new finds the buffer drained and skips the sink`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val buffer = PointBuffer<Int> { gate.await(); calls++ }
        buffer.add(1)
        launch { buffer.flush(nowMs = 1) }
        advanceUntilIdle()
        launch { buffer.flush(nowMs = 2) }
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(1L, buffer.lastFlushMs, "a skipped flush does not count as a flush")
    }

    @Test
    fun `a write in flight survives the cancellation of its scope (I2)`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val written = mutableListOf<Int>()
        val buffer = PointBuffer<Int> { batch -> gate.await(); written += batch }
        buffer.add(1)
        val job = launch { buffer.flush(nowMs = 1) }
        advanceUntilIdle()
        job.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(1), written)
        assertEquals(0, buffer.size)
    }

    @Test
    fun `a failed write keeps the items for the next attempt`() = runTest {
        var fail = true
        val written = mutableListOf<Int>()
        val buffer = PointBuffer<Int> { batch -> if (fail) error("disk full") else written += batch }
        buffer.add(1)
        assertTrue(runCatching { buffer.flush(nowMs = 1) }.isFailure)
        assertEquals(1, buffer.size)
        assertEquals(0L, buffer.lastFlushMs)
        fail = false
        buffer.flush(nowMs = 2)
        assertEquals(listOf(1), written)
        assertEquals(0, buffer.size)
    }

    @Test
    fun `flushing an empty buffer is a no-op`() = runTest {
        var calls = 0
        val buffer = PointBuffer<Int> { calls++ }
        buffer.flush(nowMs = 7)
        assertEquals(0, calls)
        assertEquals(0L, buffer.lastFlushMs)
    }
}
