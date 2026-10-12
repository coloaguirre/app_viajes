package com.bitacoraviaje.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DayBoundaryTest {
    private val tz = "America/Argentina/Buenos_Aires" // UTC-3, no DST

    @Test
    fun `next local midnight is 03 00 UTC for UTC-3`() {
        val now = Instant.parse("2026-03-14T20:00:00Z").toEpochMilli() // 17:00 local
        assertEquals(Instant.parse("2026-03-15T03:00:00Z").toEpochMilli(), DayBoundary.nextLocalMidnightUtcMs(now, tz))
    }

    @Test
    fun `just before and just after local midnight are different days`() {
        val before = Instant.parse("2026-03-15T02:59:59Z").toEpochMilli()
        val after = Instant.parse("2026-03-15T03:00:00Z").toEpochMilli()
        assertFalse(DayBoundary.isSameLocalDay(before, after, tz))
        assertTrue(DayBoundary.isSameLocalDay(before, before - 3_600_000, tz))
    }

    @Test
    fun `works across a DST change zone`() {
        val now = Instant.parse("2026-03-28T20:00:00Z").toEpochMilli() // Europe: DST starts 29 March
        val next = DayBoundary.nextLocalMidnightUtcMs(now, "Europe/Madrid")
        assertEquals(Instant.parse("2026-03-28T23:00:00Z").toEpochMilli(), next)
        val after = DayBoundary.nextLocalMidnightUtcMs(next, "Europe/Madrid")
        assertEquals(Instant.parse("2026-03-29T22:00:00Z").toEpochMilli(), after, "the DST day is 23 h long")
    }
}
