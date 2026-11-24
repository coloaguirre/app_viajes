package com.bitacoraviaje.app.tracking

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue("second flush must wait for the first", second.isActive)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(first.isCompleted && second.isCompleted)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), written)
        assertFalse(buffer.isNotEmpty())
        assertEquals(2L, buffer.lastFlushMs)
    }

    @Test
    fun `a failed write keeps the items for the next attempt`() = runTest {
        var fail = true
        val written = mutableListOf<Int>()
        val buffer = PointBuffer<Int> { batch -> if (fail) error("disk full") else written += batch }
        buffer.add(1)
        runCatching { buffer.flush(nowMs = 1) }.also { assertTrue(it.isFailure) }
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
