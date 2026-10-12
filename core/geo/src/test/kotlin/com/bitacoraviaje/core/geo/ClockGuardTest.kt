package com.bitacoraviaje.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals

class ClockGuardTest {
    @Test
    fun `consistent clocks pass through`() {
        val g = ClockGuard()
        assertEquals(1_000_000L, g.resolve(1_000_000, 50_000))
        assertEquals(1_005_000L, g.resolve(1_005_000, 55_000))
        assertEquals(0, g.corrections)
    }

    @Test
    fun `a wall clock jump backwards is corrected from the monotonic delta`() {
        val g = ClockGuard()
        g.resolve(1_000_000, 50_000)
        // user sets the phone 1 hour back; 5 s really elapsed
        assertEquals(1_005_000L, g.resolve(1_000_000 - 3_600_000 + 5_000, 55_000))
        assertEquals(1, g.corrections)
        // and the next consistent fix (still on the shifted clock) keeps the corrected timeline
        assertEquals(1_010_000L, g.resolve(1_000_000 - 3_600_000 + 10_000, 60_000))
    }

    @Test
    fun `a seeded guard corrects a clock that moved while the service was dead, and a reboot resets it`() {
        val g = ClockGuard()
        g.seed(1_000_000, 50_000)
        // 10 s later by the monotonic clock, but the wall clock went 1 h back meanwhile
        assertEquals(1_010_000L, g.resolve(1_000_000 - 3_600_000 + 10_000, 60_000))
        // monotonic went backwards: reboot -> wall clock is the truth again
        assertEquals(5_000_000L, g.resolve(5_000_000, 1_000))
        assertEquals(5_005_000L, g.resolve(5_005_000, 6_000))
    }

    @Test
    fun `forward jumps and missing monotonic values`() {
        val g = ClockGuard()
        g.resolve(1_000_000, 50_000)
        assertEquals(1_005_000L, g.resolve(1_000_000 + 7_200_000, 55_000), "2 h forward jump corrected")
        assertEquals(9_999_999L, g.resolve(9_999_999, null), "without monotonic info we trust the clock")
    }
}
