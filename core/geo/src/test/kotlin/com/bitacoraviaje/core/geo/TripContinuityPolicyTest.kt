package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TripContinuityPolicyTest {
    private val tz = "America/Argentina/Buenos_Aires"
    private val start = Instant.parse("2026-03-14T20:00:00Z").toEpochMilli() // 17:00 local
    private fun p(utcMs: Long, northM: Double) = TrackPoint(segmentId = "s", timeUtcMs = utcMs, lat = -34.6 + northM / 111_320, lon = -58.4)

    @Test
    fun `cuts the day exactly once when local midnight passes`() {
        val policy = TripContinuityPolicy(tz, start)
        val midnight = Instant.parse("2026-03-15T03:00:00Z").toEpochMilli()
        assertTrue(policy.onTick(midnight - 1).isEmpty())
        assertEquals(listOf<ContinuityAction>(ContinuityAction.CutDay), policy.onTick(midnight))
        assertTrue(policy.onTick(midnight + 60_000).isEmpty(), "no second cut until the next midnight")
        assertEquals(listOf<ContinuityAction>(ContinuityAction.CutDay), policy.onTick(midnight + 24 * 3_600_000))
    }

    @Test
    fun `a gap spanning two midnights produces a single cut`() {
        val policy = TripContinuityPolicy(tz, start)
        val twoDaysLater = start + 2 * 24 * 3_600_000
        assertEquals(listOf<ContinuityAction>(ContinuityAction.CutDay), policy.onTick(twoDaysLater))
        assertTrue(policy.onTick(twoDaysLater + 3_600_000).isEmpty())
    }

    @Test
    fun `points drive rest enter and exit, and midnight cut comes through points too`() {
        val policy = TripContinuityPolicy(tz, start)
        var t = start
        val actions = ArrayList<ContinuityAction>()
        repeat(80) { i ->
            actions.addAll(policy.onPoint(p(t, if (i % 2 == 0) 0.0 else 5.0)))
            t += 5_000
        } // ~6.5 min still
        assertEquals(listOf<ContinuityAction>(ContinuityAction.EnterRest), actions)
        assertTrue(policy.isResting)
        actions.clear()
        actions.addAll(policy.onPoint(p(t, 100.0)))
        assertEquals(listOf<ContinuityAction>(ContinuityAction.ExitRest), actions)
        val afterMidnight = Instant.parse("2026-03-15T03:00:05Z").toEpochMilli()
        assertEquals(listOf<ContinuityAction>(ContinuityAction.CutDay), policy.onPoint(p(afterMidnight, 200.0)))
    }

    @Test
    fun `ticks without points enter rest and a midnight tick during rest cuts the day too`() {
        val policy = TripContinuityPolicy(tz, start)
        policy.onPoint(p(start, 0.0))
        assertTrue(policy.onTick(start + 4 * 60_000).isEmpty())
        assertEquals(listOf<ContinuityAction>(ContinuityAction.EnterRest), policy.onTick(start + 5 * 60_000))
        val midnight = Instant.parse("2026-03-15T03:00:00Z").toEpochMilli()
        assertEquals(listOf<ContinuityAction>(ContinuityAction.CutDay), policy.onTick(midnight))
        assertEquals(listOf<ContinuityAction>(ContinuityAction.ExitRest), policy.onPoint(p(midnight + 1_000, 200.0)))
    }

    @Test
    fun `day cutting can be disabled for nested excursions`() {
        val policy = TripContinuityPolicy(tz, start, cutDays = false)
        assertTrue(policy.onTick(start + 48 * 3_600_000).isEmpty())
    }
}
