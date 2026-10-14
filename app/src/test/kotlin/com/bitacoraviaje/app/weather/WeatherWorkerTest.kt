package com.bitacoraviaje.app.weather

import kotlin.test.Test
import kotlin.test.assertEquals

/** Backlog D3 (review I2/I5): a segment closed minutes ago waits for its hour to end before asking. */
class WeatherWorkerTest {
    private val h = 3_600_000L
    private val hour13 = 1_791_810_000_000L / h * h // some 13:00 UTC

    @Test
    fun `delay runs to the end of the closing hour plus the settle margin, never negative`() {
        val closed = hour13 + 13 * 60_000 // 13:13
        assertEquals(47 * 60_000L + WeatherWorker.SETTLE_MS, WeatherWorker.delayMs(closed, closed), "closed at 13:13 → wait until 14:15")
        assertEquals(0L, WeatherWorker.delayMs(closed, hour13 + 2 * h), "two hours later: ask now")
        assertEquals(0L, WeatherWorker.delayMs(null, closed), "no end known: ask now")
        assertEquals(WeatherWorker.SETTLE_MS, WeatherWorker.delayMs(hour13 + h - 1, hour13 + h), "closed at 13:59:59, now 14:00 → the settle margin only")
    }
}
